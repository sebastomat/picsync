// Package bridge exposes a small, gomobile-friendly API around whatsmeow.
// It only does what PicSync needs: link as a companion device and fetch
// profile pictures of phone numbers.
package bridge

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	waLog "go.mau.fi/whatsmeow/util/log"
	_ "modernc.org/sqlite"
)

// Listener receives asynchronous events. Implemented on the Kotlin side.
type Listener interface {
	OnPairCode(code string)
	OnPaired(jid string)
	// OnPairError is called if pairing ends without success (e.g. "timeout").
	OnPairError(reason string)
	OnConnected()
	OnLoggedOut()
	OnLog(level string, msg string)
}

// Picture is a downloaded profile picture.
// Data is empty if the picture did not change compared to the ID passed in.
type Picture struct {
	ID   string
	Data []byte
}

type Client struct {
	listener  Listener
	container *sqlstore.Container
	cli       *whatsmeow.Client
	http      *http.Client
	mu        sync.Mutex
}

const resolveChunkSize = 50

func init() {
	store.SetOSInfo("PicSync", [3]uint32{0, 1, 0})
	store.DeviceProps.RequireFullSync = new(bool)
}

// NewClient opens (or creates) the session database at dbPath.
func NewClient(dbPath string, l Listener) (*Client, error) {
	log := &listenerLogger{l: l, module: "wa"}
	db, err := sql.Open("sqlite", "file:"+dbPath+"?_pragma=foreign_keys(1)&_pragma=busy_timeout(5000)")
	if err != nil {
		return nil, fmt.Errorf("open db: %w", err)
	}
	container := sqlstore.NewWithDB(db, "sqlite3", log.Sub("db"))
	ctx := context.Background()
	if err = container.Upgrade(ctx); err != nil {
		return nil, fmt.Errorf("upgrade db: %w", err)
	}
	device, err := container.GetFirstDevice(ctx)
	if err != nil {
		return nil, fmt.Errorf("load device: %w", err)
	}
	cli := whatsmeow.NewClient(device, log.Sub("client"))
	// We never need chat history, so don't download it.
	cli.ManualHistorySyncDownload = true

	c := &Client{
		listener:  l,
		container: container,
		cli:       cli,
		http:      &http.Client{Timeout: 30 * time.Second},
	}
	cli.AddEventHandler(c.handleEvent)
	return c, nil
}

func (c *Client) handleEvent(evt any) {
	switch v := evt.(type) {
	case *events.PairSuccess:
		c.listener.OnPaired(v.ID.String())
	case *events.Connected:
		c.listener.OnConnected()
	case *events.LoggedOut:
		c.listener.OnLoggedOut()
	}
}

// IsPaired reports whether a linked-device session exists.
func (c *Client) IsPaired() bool {
	return c.cli.Store.ID != nil
}

// IsConnected reports whether the websocket is connected and logged in.
func (c *Client) IsConnected() bool {
	return c.cli.IsConnected() && c.cli.IsLoggedIn()
}

// Connect connects an already paired session.
func (c *Client) Connect() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.IsPaired() {
		return errors.New("not paired")
	}
	if c.cli.IsConnected() {
		return nil
	}
	return c.cli.Connect()
}

// PairWithPhone starts linking via pairing code. phone is the own number in
// international format, digits only (e.g. 4917612345678). The code is delivered
// through Listener.OnPairCode and must be entered in WhatsApp under
// "Linked devices > Link with phone number instead".
func (c *Client) PairWithPhone(phone string) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.IsPaired() {
		return errors.New("already paired")
	}
	phone = digitsOnly(phone)
	if phone == "" {
		return errors.New("invalid phone number")
	}
	c.cli.Disconnect()
	// The QR channel must be requested before connecting; it drives the login socket.
	qrChan, err := c.cli.GetQRChannel(context.Background())
	if err != nil {
		return fmt.Errorf("qr channel: %w", err)
	}
	if err = c.cli.Connect(); err != nil {
		return fmt.Errorf("connect: %w", err)
	}
	// Wait for the first QR item, which signals that the socket is ready.
	first, ok := <-qrChan
	if !ok || first.Event != whatsmeow.QRChannelEventCode {
		return fmt.Errorf("pairing not possible: %s", first.Event)
	}
	go func() {
		for item := range qrChan {
			switch {
			case item.Event == whatsmeow.QRChannelEventCode, item == whatsmeow.QRChannelSuccess:
			case item.Error != nil:
				c.listener.OnPairError(item.Event + ": " + item.Error.Error())
			default:
				c.listener.OnPairError(item.Event)
			}
		}
	}()
	code, err := c.cli.PairPhone(context.Background(), phone, true, whatsmeow.PairClientChrome, "Chrome (Linux)")
	if err != nil {
		return fmt.Errorf("pair phone: %w", err)
	}
	c.listener.OnPairCode(code)
	return nil
}

// Disconnect closes the connection but keeps the session.
func (c *Client) Disconnect() {
	c.cli.Disconnect()
}

// Logout unlinks the device and deletes the session.
func (c *Client) Logout() error {
	return c.cli.Logout(context.Background())
}

// ResolveNumbers checks which phone numbers are on WhatsApp.
// Input: newline-separated numbers in international format (+4917612345678).
// Output: newline-separated "query<TAB>jid" lines for registered numbers only.
func (c *Client) ResolveNumbers(phones string) (string, error) {
	var queries []string
	for _, p := range strings.Split(phones, "\n") {
		if d := digitsOnly(p); d != "" {
			queries = append(queries, "+"+d)
		}
	}
	var out strings.Builder
	for start := 0; start < len(queries); start += resolveChunkSize {
		end := min(start+resolveChunkSize, len(queries))
		resp, err := c.cli.IsOnWhatsApp(context.Background(), queries[start:end])
		if err != nil {
			return out.String(), fmt.Errorf("is on whatsapp: %w", err)
		}
		for _, r := range resp {
			if r.IsIn {
				fmt.Fprintf(&out, "%s\t%s\n", r.Query, r.JID.String())
			}
		}
		if end < len(queries) {
			time.Sleep(time.Second)
		}
	}
	return out.String(), nil
}

// FetchPicture downloads the full-size profile picture of jid.
// Returns nil if the user has no (visible) picture. If existingID matches the
// current picture, a Picture with that ID and empty Data is returned.
func (c *Client) FetchPicture(jid string, existingID string) (*Picture, error) {
	target, err := types.ParseJID(jid)
	if err != nil {
		return nil, fmt.Errorf("parse jid: %w", err)
	}
	info, err := c.cli.GetProfilePictureInfo(context.Background(), target, &whatsmeow.GetProfilePictureParams{
		ExistingID: existingID,
	})
	switch {
	case errors.Is(err, whatsmeow.ErrProfilePictureNotSet), errors.Is(err, whatsmeow.ErrProfilePictureUnauthorized):
		return nil, nil
	case err != nil:
		return nil, fmt.Errorf("picture info: %w", err)
	case info == nil:
		return &Picture{ID: existingID}, nil
	}
	resp, err := c.http.Get(info.URL)
	if err != nil {
		return nil, fmt.Errorf("download: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("download: http %d", resp.StatusCode)
	}
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("download: %w", err)
	}
	return &Picture{ID: info.ID, Data: data}, nil
}

func digitsOnly(s string) string {
	var b strings.Builder
	for _, r := range s {
		if r >= '0' && r <= '9' {
			b.WriteRune(r)
		}
	}
	return b.String()
}

type listenerLogger struct {
	l      Listener
	module string
}

func (g *listenerLogger) log(level, msg string, args ...any) {
	g.l.OnLog(level, g.module+": "+fmt.Sprintf(msg, args...))
}

func (g *listenerLogger) Warnf(msg string, args ...any)  { g.log("warn", msg, args...) }
func (g *listenerLogger) Errorf(msg string, args ...any) { g.log("error", msg, args...) }
func (g *listenerLogger) Infof(msg string, args ...any)  { g.log("info", msg, args...) }
func (g *listenerLogger) Debugf(string, ...any)          {}
func (g *listenerLogger) Sub(module string) waLog.Logger {
	return &listenerLogger{l: g.l, module: g.module + "/" + module}
}
