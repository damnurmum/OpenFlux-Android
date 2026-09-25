package cupsonline

import (
	"testing"

	"openflux/transport"
)

// A Session stops a carrier through every wrapper around it, so Stop comes
// twice; that used to panic with "close of closed channel" and take the
// Android app down.
func TestStopTwice(t *testing.T) {
	c := NewCupsonlineTransport("", transport.TransportConfig{}, false)
	if err := c.Stop(); err != nil {
		t.Fatal(err)
	}
	if err := c.Stop(); err != nil {
		t.Fatal(err)
	}
}
