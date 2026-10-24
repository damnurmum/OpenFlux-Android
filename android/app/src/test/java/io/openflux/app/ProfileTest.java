package io.openflux.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class ProfileTest {

    @Test
    public void savedProfileReadsBackUnchanged() throws Exception {
        Profile p = new Profile();
        p.id = 42;
        p.name = "Дом";
        p.transportType = "mailru";
        p.documentUrl = "https://cloud.mail.ru/public/a/b";
        p.encryptionSecret = "secret";
        p.codec = "legacy";
        p.session = true;
        p.priority = 70;
        p.context = "ctx";
        p.settings.put("room", "1");
        Profile.Transport extra = new Profile.Transport();
        extra.type = "direct";
        extra.value = "1.2.3.4:8445";
        extra.priority = 10;
        extra.settings.put("k", "v");
        p.extraTransports.add(extra);

        Profile back = Profile.fromJson(new JSONObject(p.toJson().toString()));

        assertEquals(p.toJson().toString(), back.toJson().toString());
        assertEquals("1.2.3.4:8445", back.extraTransports.get(0).value);
        assertEquals("v", back.extraTransports.get(0).settings.getString("k"));
    }

    // Profiles saved by older versions lack the newer fields.
    @Test
    public void oldProfileGetsDefaults() throws Exception {
        Profile p = Profile.fromJson(new JSONObject()
                .put("name", "Старый")
                .put("documentUrl", "https://docs.yandex.ru/d/x"));

        assertEquals("Старый", p.name);
        assertEquals("yandex", p.transportType);
        assertEquals("batched", p.codec);
        assertEquals("ic_public", p.icon);
        assertEquals(50, p.priority);
        assertFalse(p.session);
        assertFalse(p.stream);
        assertTrue(p.extraTransports.isEmpty());
        assertEquals(0, p.settings.length());
    }

    @Test
    public void shareLinkMainIsTheTopNonDirectTransport() throws Exception {
        JSONObject link = new JSONObject()
                .put("name", "Нода")
                .put("negotiate", true)
                .put("secret", "s")
                .put("context", "c")
                .put("transports", new JSONArray()
                        .put(new JSONObject().put("type", "cupsonline").put("url", "rooms").put("priority", 80))
                        .put(new JSONObject().put("type", "direct").put("dial", "5.6.7.8:9").put("priority", 100))
                        .put(new JSONObject().put("type", "mailru").put("url", "https://m").put("priority", 90)));

        Profile p = Profile.fromShare(link);

        assertEquals("Нода", p.name);
        assertTrue(p.session);
        assertEquals("c", p.context);
        assertEquals("mailru", p.transportType);
        assertEquals("https://m", p.documentUrl);
        assertEquals(90, p.priority);
        assertEquals(2, p.extraTransports.size());
        assertEquals("direct", p.extraTransports.get(0).type);
        assertEquals("5.6.7.8:9", p.extraTransports.get(0).value);
        assertEquals("cupsonline", p.extraTransports.get(1).type);
    }

    @Test
    public void shareLinkModesAndDefaults() throws Exception {
        JSONObject link = new JSONObject()
                .put("mode", "stream")
                .put("codec", "legacy")
                .put("context", "ignored without Session")
                .put("transports", new JSONArray()
                        .put(new JSONObject().put("type", "cupsonline").put("url", "room")));

        Profile p = Profile.fromShare(link);

        assertEquals("OpenFlux", p.name);
        assertTrue(p.stream);
        assertFalse(p.session);
        assertEquals("legacy", p.codec);
        assertEquals("", p.context);
    }

    // Names follow the CLI (type, type-2, ...), which the exit matches on.
    @Test
    public void sessionTransportsAreNamedAndFilledLikeTheCli() throws Exception {
        Profile p = new Profile();
        p.session = true;
        p.transportType = "mailru";
        p.documentUrl = "https://m/1";
        Profile.Transport second = new Profile.Transport();
        second.type = "mailru";
        second.value = "https://m/2";
        Profile.Transport direct = new Profile.Transport();
        direct.type = "direct";
        direct.value = "1.2.3.4:5";
        Profile.Transport max = new Profile.Transport();
        max.type = "oneme";
        max.value = "token";
        max.uid = "77";
        p.extraTransports.add(second);
        p.extraTransports.add(direct);
        p.extraTransports.add(max);

        JSONArray specs = new JSONArray(p.sessionTransportsJson(null));

        assertEquals("mailru", specs.getJSONObject(0).getString("name"));
        assertEquals("mailru-2", specs.getJSONObject(1).getString("name"));
        assertEquals("https://m/2", specs.getJSONObject(1).getString("url"));
        assertEquals("1.2.3.4:5", specs.getJSONObject(2).getJSONObject("params").getString("dial"));
        assertEquals("token", specs.getJSONObject(3).getJSONObject("params").getString("token"));
        assertEquals("77", specs.getJSONObject(3).getJSONObject("params").getString("uid"));
    }

    @Test
    public void sessionContextWrapsTheTransports() throws Exception {
        Profile p = new Profile();
        p.session = true;
        p.transportType = "mailru";
        p.documentUrl = "https://m";
        p.context = "ctx";

        JSONObject spec = new JSONObject(p.sessionTransportsJson(null));

        assertEquals("ctx", spec.getString("context"));
        assertEquals(1, spec.getJSONArray("transports").length());
    }

    @Test
    public void telemostComLinkBecomesRu() {
        assertEquals("https://telemost.yandex.ru/j/123",
                Profile.carrierValue("telemost", " https://telemost.yandex.com/j/123 "));
        assertEquals("https://telemost.yandex.com/j/123",
                Profile.carrierValue("mailru", "https://telemost.yandex.com/j/123"));
    }

    @Test
    public void carriersLabelNamesRepeatsAndKeepsUnknownNames() {
        assertEquals("Yandex Board 2, Mail.ru Docs", Profile.carriersLabel("boards-2,mailru", ""));
        assertEquals("Cups.online", Profile.carriersLabel("", "cupsonline"));
        assertEquals("my-carrier", Profile.carriersLabel("my-carrier", ""));
    }

    @Test
    public void jsCarrierNeedsSessionAndAPickedScript() {
        Profile p = new Profile();
        p.transportType = "script";
        assertEquals("JS-транспорт работает только в режиме Session", p.scriptProblem(null));
        p.session = true;
        assertEquals("Выберите JS-транспорт", p.scriptProblem(null));

        Profile plain = new Profile();
        assertNull(plain.scriptProblem(null));
    }
}
