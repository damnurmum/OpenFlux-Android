package io.openflux.app;

// The VPN advertises an IPv4 resolver that the exit node can reach. Parsing
// must not perform a lookup on the phone before the VPN is established.
final class DnsServer {
    static final String DEFAULT = "1.1.1.1";

    private DnsServer() { }

    static String effective(String configured) {
        return configured == null || configured.trim().isEmpty()
                ? DEFAULT : normalizeIpv4(configured);
    }

    static String normalizeIpv4(String value) {
        if (value == null) return null;
        String[] octets = value.trim().split("\\.", -1);
        if (octets.length != 4) return null;
        StringBuilder normalized = new StringBuilder();
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) return null;
            int number = 0;
            for (int i = 0; i < octet.length(); i++) {
                char digit = octet.charAt(i);
                if (digit < '0' || digit > '9') return null;
                number = number * 10 + digit - '0';
            }
            if (number > 255) return null;
            if (normalized.length() > 0) normalized.append('.');
            normalized.append(number);
        }
        return normalized.toString();
    }
}
