// The "Создать свою ноду" wizard: installs an exit channel on the user's VDS
// over SSH (package provision), checks the channel's Yandex document and
// proves the finished channel end to end before the app saves a profile.
// Every call is blocking; the app runs them off the UI thread. Results are
// JSON objects with "ok" and, on failure, a user-readable "error".
package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"openflux/provision"
	"openflux/share"
	"openflux/transport"
	"openflux/transport/yandex"
	"openflux/tunnel"
)

var node struct {
	mu   sync.Mutex
	conn *provision.Conn

	verifyCancel context.CancelFunc
}

func result(fields map[string]interface{}) string {
	if fields == nil {
		fields = map[string]interface{}{}
	}
	if _, ok := fields["ok"]; !ok {
		fields["ok"] = true
	}
	b, _ := json.Marshal(fields)
	return string(b)
}

func failure(err error, extra map[string]interface{}) string {
	fields := map[string]interface{}{"ok": false, "error": err.Error()}
	for k, v := range extra {
		fields[k] = v
	}
	return result(fields)
}

// NodeConnect opens SSH to the VDS, has it download the pinned installer and
// probes it. hostKey is the fingerprint the user trusted before, "" for a
// new server: then the result has "hostKey" with the fingerprint to show and
// "trust": true, and the app calls again with it once the user agrees. A
// changed key comes back with "mismatch": true and must not be trusted
// silently.
func NodeConnect(host string, port int, user, password, privateKey, passphrase, hostKey string) string {
	NodeDisconnect()
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	conn, err := provision.Dial(ctx, provision.Target{
		Host: strings.TrimSpace(host), Port: port, User: strings.TrimSpace(user),
		Password: password, PrivateKey: privateKey, Passphrase: passphrase, HostKey: hostKey,
	})
	if err != nil {
		var hk *provision.HostKeyError
		if errors.As(err, &hk) {
			return failure(err, map[string]interface{}{"hostKey": hk.Fingerprint, "trust": !hk.Mismatch, "mismatch": hk.Mismatch})
		}
		return failure(err, nil)
	}
	appendLog("[NODE] SSH: подключено, загрузка скрипта установки")
	if err := conn.FetchScript(provision.Pinned()); err != nil {
		conn.Close()
		return failure(err, nil)
	}
	probe, err := conn.Probe()
	if err != nil {
		conn.Close()
		return failure(err, nil)
	}
	if !probe.Systemd {
		conn.Close()
		return failure(errors.New("на сервере нет systemd: мастер поддерживает Debian, Ubuntu и похожие системы"), nil)
	}
	if probe.Sudo == "none" {
		conn.Close()
		return failure(errors.New("у пользователя нет root и sudo: войдите как root или пользователь с sudo"), nil)
	}
	node.mu.Lock()
	node.conn = conn
	node.mu.Unlock()
	appendLog(fmt.Sprintf("[NODE] Сервер: %s %s, каналов OpenFlux: %d", probe.OS, probe.Arch, len(probe.Channels)))
	return result(map[string]interface{}{"probe": probe})
}

// NodeDisconnect closes the SSH connection, removing the downloaded script.
func NodeDisconnect() {
	node.mu.Lock()
	conn := node.conn
	node.conn = nil
	node.mu.Unlock()
	if conn != nil {
		conn.Close()
	}
}

func nodeConn() (*provision.Conn, error) {
	node.mu.Lock()
	defer node.mu.Unlock()
	if node.conn == nil {
		return nil, errors.New("нет подключения к серверу")
	}
	return node.conn, nil
}

// NodeNewChannel returns a fresh channel name and key: {"id", "key"}.
func NodeNewChannel() string {
	id, err := provision.NewChannelID()
	if err != nil {
		return failure(err, nil)
	}
	key, err := provision.NewKey()
	if err != nil {
		return failure(err, nil)
	}
	return result(map[string]interface{}{"id": id, "key": key})
}

// NodePlan asks the VDS what installing the channel would change: {"plan"}.
// withCookies: the Yandex sign-in goes to the node too.
func NodePlan(channel string, port int, withCookies bool) string {
	conn, err := nodeConn()
	if err != nil {
		return failure(err, nil)
	}
	plan, err := conn.Plan(channel, port, withCookies)
	if err != nil {
		return failure(err, nil)
	}
	return result(map[string]interface{}{"plan": plan})
}

// NodeApply installs and starts the channel. sudoPassword is only used when
// the account needs one; a wrong one comes back with "sudo": true.
// cookieHeader is the Yandex sign-in for the node ("" for none): the node
// then opens the document as that account, which gets it past the checks
// Yandex shows a server's address.
func NodeApply(channel, documentURL, key string, port int, sudoPassword, cookieHeader string) string {
	conn, err := nodeConn()
	if err != nil {
		return failure(err, nil)
	}
	ch := provision.Channel{ID: channel, URL: documentURL, Key: key, Port: port}
	if cookieHeader != "" {
		if ch.Cookies, _, err = provision.CookieStore(documentURL, cookieHeader); err != nil {
			return failure(err, nil)
		}
	}
	appendLog("[NODE] Установка канала " + channel)
	if err := conn.Apply(ch, sudoPassword); err != nil {
		appendLog("[NODE] Установка не удалась")
		return failure(err, map[string]interface{}{"sudo": errors.Is(err, provision.ErrSudoPassword)})
	}
	appendLog("[NODE] Канал " + channel + " запущен")
	return result(nil)
}

// NodeSetCookies gives an installed channel's node a new Yandex sign-in
// and restarts it.
func NodeSetCookies(channel, documentURL, cookieHeader, sudoPassword string) string {
	conn, err := nodeConn()
	if err != nil {
		return failure(err, nil)
	}
	cookies, _, err := provision.CookieStore(documentURL, cookieHeader)
	if err != nil {
		return failure(err, nil)
	}
	if err := conn.SetCookies(channel, cookies, sudoPassword); err != nil {
		return failure(err, map[string]interface{}{"sudo": errors.Is(err, provision.ErrSudoPassword)})
	}
	appendLog("[NODE] Вход в Яндекс передан каналу " + channel)
	return result(nil)
}

// NodeSignedIn reports whether a WebView Cookie header holds a Yandex login.
func NodeSignedIn(cookieHeader string) bool {
	_, signedIn, err := provision.CookieStore("x", cookieHeader)
	return err == nil && signedIn
}

// NodeRemove deletes the channel from the VDS (a failed verification's
// cleanup).
func NodeRemove(channel, sudoPassword string) string {
	conn, err := nodeConn()
	if err != nil {
		return failure(err, nil)
	}
	if err := conn.Remove(channel, sudoPassword); err != nil {
		return failure(err, map[string]interface{}{"sudo": errors.Is(err, provision.ErrSudoPassword)})
	}
	appendLog("[NODE] Канал " + channel + " удалён с сервера")
	return result(nil)
}

// NodeCheckDocument tells whether the vyandex transport can use the
// document, as an anonymous visitor like the node: {"editable"}. A check
// Yandex wants a person to pass comes back with "captcha": true.
func NodeCheckDocument(documentURL string) string {
	doc, err := yandex.CheckVolgaDocument(documentURL, nil)
	if err != nil {
		captcha := errors.Is(err, yandex.ErrCaptchaRequired) || errors.Is(err, yandex.ErrLoginRequired)
		msg := err
		switch {
		case captcha:
			msg = errors.New("Яндекс просит пройти проверку, повторите через минуту")
		case strings.Contains(err.Error(), "client-config"), strings.Contains(err.Error(), "officeActionData"):
			msg = errors.New("документ не открылся в редакторе Яндекса: проверьте доступ по ссылке")
		}
		return failure(msg, map[string]interface{}{"captcha": captcha})
	}
	if !doc.Editable {
		return failure(errors.New("по ссылке документ открывается только на просмотр, нужен доступ на редактирование"), nil)
	}
	return result(map[string]interface{}{"editable": true})
}

// NodeVerify proves a new channel end to end: it starts a Session client
// for specsJSON (as Profile.sessionTransportsJson builds it) and fetches
// https://api.ipify.org through the tunnel, expecting the VDS's address
// (expectHost, resolved here). The node's own Yandex check shows up in
// PendingCaptchaURL like on a regular connect. Returns {"ip"}. Only one
// connection can run at a time, so the app stops its tunnel first.
func NodeVerify(specsJSON, secret, expectHost string, timeoutSec int) string {
	if ProxyIsRunning() || ExitIsRunning() || packetRunning() {
		return failure(errors.New("сначала отключите текущее соединение OpenFlux"), nil)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutSec)*time.Second)
	node.mu.Lock()
	node.verifyCancel = cancel
	node.mu.Unlock()
	defer func() {
		cancel()
		node.mu.Lock()
		node.verifyCancel = nil
		node.mu.Unlock()
	}()

	want := map[string]bool{}
	if ip := net.ParseIP(expectHost); ip != nil {
		want[ip.String()] = true
	} else if addrs, err := net.DefaultResolver.LookupHost(ctx, expectHost); err == nil {
		for _, a := range addrs {
			want[a] = true
		}
	}

	appendLog("[NODE] Проверка канала: подключение")
	trans, sess, err := buildSessionWith(specsJSON, secret, false)
	if err != nil {
		return failure(err, nil)
	}
	// Same order as StopProxy: the captcha side first, then the carriers.
	defer func() {
		detachCaptcha()
		CancelCaptcha()
		setAuthProxy(nil)
		clearRoute()
		_ = trans.Stop()
	}()
	if err := trans.Start(); err != nil {
		return failure(err, nil)
	}
	for !trans.IsConnected() {
		select {
		case <-ctx.Done():
			return failure(errors.New("нода не ответила: проверьте, что документ и ключ совпадают и сервер доступен"), nil)
		case <-time.After(300 * time.Millisecond):
		}
	}
	ip, err := fetchIP(ctx, trans)
	if err != nil {
		return failure(fmt.Errorf("канал поднялся, но запрос через него не прошёл: %v", err), nil)
	}
	appendLog("[NODE] Проверка канала: внешний адрес " + ip)
	if len(want) > 0 && !want[ip] {
		return failure(fmt.Errorf("запрос вышел с адреса %s, а не с адреса сервера", ip), map[string]interface{}{"ip": ip})
	}
	// Traffic may have gone through the backup carrier. Give the primary
	// (highest priority) one the rest of the time to come up: the node may
	// first need its own Yandex check passed, which reaches the app over the
	// backup carrier as a pending captcha.
	names := sess.Transports()
	primary := len(names) > 0 && waitLive(ctx, sess, names[0])
	return result(map[string]interface{}{"ip": ip, "primary": primary, "live": sess.LiveTransports()})
}

func waitLive(ctx context.Context, sess *transport.Session, name string) bool {
	for {
		for _, n := range sess.LiveTransports() {
			if n == name {
				return true
			}
		}
		select {
		case <-ctx.Done():
			return false
		case <-time.After(500 * time.Millisecond):
		}
	}
}

// NodeCancelVerify stops a running NodeVerify.
func NodeCancelVerify() {
	node.mu.Lock()
	cancel := node.verifyCancel
	node.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

func packetRunning() bool {
	client.mu.Lock()
	defer client.mu.Unlock()
	return client.running
}

func fetchIP(ctx context.Context, trans transport.Transport) (string, error) {
	tun := tunnel.NewTCPTunnel(trans, false)
	defer tun.Close()
	httpClient := &http.Client{
		Transport: &http.Transport{
			DialContext: func(_ context.Context, _, addr string) (net.Conn, error) {
				return tun.DialTCP(addr)
			},
			TLSHandshakeTimeout: 20 * time.Second,
		},
		Timeout: 45 * time.Second,
	}
	var lastErr error
	for attempt := 0; attempt < 4; attempt++ {
		req, _ := http.NewRequestWithContext(ctx, "GET", "https://api.ipify.org", nil)
		resp, err := httpClient.Do(req)
		if err == nil {
			body, rerr := io.ReadAll(io.LimitReader(resp.Body, 64))
			resp.Body.Close()
			ip := strings.TrimSpace(string(body))
			if rerr == nil && resp.StatusCode == 200 && net.ParseIP(ip) != nil {
				return ip, nil
			}
			err = fmt.Errorf("ответ %d", resp.StatusCode)
		}
		lastErr = err
		select {
		case <-ctx.Done():
			return "", lastErr
		case <-time.After(2 * time.Second):
		}
	}
	return "", lastErr
}

// NodeShareLink returns the openflux:// link of a new channel: the Session
// profile a client needs (Yandex document first, direct to host:port as the
// backup). The app saves it through the same import path as a scanned QR,
// and shows it as a QR for another device. It carries the channel key.
func NodeShareLink(name, documentURL, key, host string, port int) (string, error) {
	return share.Encode(share.Config{
		Name:      name,
		Negotiate: true,
		Secret:    key,
		Context:   documentURL,
		Transports: []share.Transport{
			{Type: "vyandex", URL: documentURL, Priority: 100},
			{Type: "direct", Dial: net.JoinHostPort(host, strconv.Itoa(port)), Priority: 50},
		},
	})
}
