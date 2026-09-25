package utils

import (
	"fmt"
	"log"
	"os"
	"sync"
	"sync/atomic"
)

// verbose is atomic and the logger is created once because the mobile
// bridge enables debug on every start, while goroutines of the previous
// connection may still be logging.
var (
	debugLog  = log.New(os.Stderr, "", log.LstdFlags|log.Lmicroseconds)
	verbose   atomic.Bool
	logSinkMu sync.RWMutex
	logSink   func(string)
)

func EnableDebug() {
	verbose.Store(true)
	log.SetFlags(log.LstdFlags | log.Lmicroseconds | log.Lshortfile)
}

// SetDebug toggles verbose logging at runtime (off = Debugf becomes a no-op).
func SetDebug(on bool) {
	if on {
		EnableDebug()
		return
	}
	verbose.Store(false)
}

func Debugf(format string, args ...interface{}) {
	if verbose.Load() {
		message := fmt.Sprintf(format, args...)
		debugLog.Output(2, message)

		logSinkMu.RLock()
		sink := logSink
		logSinkMu.RUnlock()
		if sink != nil {
			sink(message)
		}
	}
}

// Infof always logs, regardless of verbose mode. Used for user-facing status
// lines (e.g. cups room open/close) that must be visible without --debug.
func Infof(format string, args ...interface{}) {
	log.Output(2, fmt.Sprintf(format, args...))
}

// SetLogSink mirrors debug messages to an embedding application.
func SetLogSink(sink func(string)) {
	logSinkMu.Lock()
	logSink = sink
	logSinkMu.Unlock()
}

func IsVerbose() bool {
	return verbose.Load()
}

// SafeGo runs fn in a new goroutine, recovering from any panic so a crash in
// one worker cannot take down the whole process (critical when this code runs
// embedded as a library inside a mobile app).
func SafeGo(name string, fn func()) {
	go func() {
		defer func() {
			if r := recover(); r != nil {
				Debugf("[PANIC] recovered in %s: %v", name, r)
			}
		}()
		fn()
	}()
}
