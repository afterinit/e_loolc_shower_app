package com.loolc.shower_app;

import android.annotation.SuppressLint;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** 完成校园登录、CAS 票据交换与浴室网关请求。 */
final class BathClient {
    private static final String MCP_HOST = "mcp.hbmzu.edu.cn";
    private static final String MCP = "http://" + MCP_HOST + ":8090/mobileapi_ydxy";
    private static final String CAS_SCHOOL = "https://cas.hbmzu.edu.cn";
    private static final String CAS_WX = "https://cas.hzxwwl.com/auth/cas/sso";
    private static final String GATEWAY = "https://gateway.hzxwwl.com";
    private static final String WX_ORIGIN = "https://wx.hzxwwl.com";
    private static final String SCHOOL_ID = "119";
    private static final String CLIENT_VERSION = "3.9.8";
    private static final String UA_APP = "Dalvik/2.1.0 (Linux; U; Android 16; PKG110 Build/UKQ1.231108.001)";
    private static final String UA_WEB = "Mozilla/5.0 (Linux; Android 16; PKG110 Build/UKQ1.231108.001; wv) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/151.0.7922.199 Mobile Safari/537.36;lyydxy";
    private static final String X_REQUESTED_WITH = "com.lysoft.android.lyyd.report.mobile.hbmz";
    private static final String BATH_ACCOUNT = GATEWAY + "/api/water/bath/account/";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern UNESCAPED_URL = Pattern.compile("%(?![0-9a-fA-F]{2})|[\\p{Cntrl} \"<>\\\\^`{|}]");

    private final CookieManager cookies = new CookieManager();

    SchoolSession signInToSchool(String account, String password) throws Exception {
        URI keyUrl = URI.create(MCP + "/open/security/asym/key");
        Response publicResponse = request(keyUrl, headers("Asym-Key", "public", "User-Agent", UA_APP),
                null, "获取学校登录密钥");
        byte[] aes = new byte[16];
        RANDOM.nextBytes(aes);
        String publicKey = publicResponse.requiredData(publicResponse.json());
        String envelope;
        try {
            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA").generatePublic(
                    new X509EncodedKeySpec(decode(publicKey))));
            byte[] keyBody = new JSONObject().put("sk", encode(aes)).toString().getBytes(StandardCharsets.UTF_8);
            envelope = encode(rsa.doFinal(keyBody));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new BathCode.Failure("学校登录公钥无法使用，请稍后重试。");
        }
        Response exchange = request(keyUrl, headers("Asym-Key", "private", "User-Agent", UA_APP,
                "Content-Type", "text/plain"), envelope, "交换学校登录密钥");
        String uniqueCode = decrypt(aes, exchange.requiredData(exchange.json()), exchange.action);
        if (uniqueCode.isEmpty() || !uniqueCode.matches("[!-~]+")) {
            throw new BathCode.Failure("学校密钥交换返回的登录标识格式异常。");
        }

        JSONObject payload = new JSONObject()
                .put("androidClientVersionCode", "999").put("city", "").put("clientVersion", CLIENT_VERSION)
                .put("deviceId", deviceId()).put("deviceModel", "PKG110").put("deviceType", "1")
                .put("deviceVersion", "16").put("latitude", "").put("longitude", "").put("phoneNum", "")
                .put("province", "").put("serverApiVersion", CLIENT_VERSION).put("passw", password)
                .put("schoolId", SCHOOL_ID).put("userId", account);
        Response login = request(URI.create(MCP + "/open/auth/login"), headers(
                "Access-Token", encrypt(aes, accessToken("", "", "", SCHOOL_ID)), "Unique-Code", uniqueCode,
                "AppType", "ydxy", "Content-Type", "text/plain", "User-Agent", UA_APP),
                encrypt(aes, payload), "学校账号登录");
        if (login.status == 401) throw new SignInRequired("学校登录失败，请核对账号和密码。");
        JSONObject result = login.json();
        if (!"0".equals(result.optString("code"))) {
            String error = message(result, "学校账号验证失败，请核对账号和密码后重试。");
            if ("401".equals(result.optString("code")) || error.contains("密码错误")
                    || error.contains("账号或密码") || error.contains("账户或密码")
                    || error.contains("账号不存在") || error.contains("用户不存在")) {
                throw new SignInRequired(error);
            }
            throw new BathCode.Failure(error);
        }
        JSONObject user;
        try {
            user = new JSONObject(decrypt(aes, login.requiredData(result), login.action));
        } catch (JSONException exception) {
            throw new BathCode.Failure("学校登录返回的用户信息格式异常，请稍后重试。");
        }
        String session = field(user, "session_key");
        if (session.isEmpty()) session = field(user, "sessionKey");
        if (session.isEmpty()) throw new BathCode.Failure("学校登录服务未返回会话，请稍后重试。");
        String school = field(user, "schoolId");
        return new SchoolSession(aes, uniqueCode, session, field(user, "userId"), field(user, "userType"),
                school.isEmpty() ? SCHOOL_ID : school);
    }

    String openBathSession(String account, SchoolSession school) throws Exception {
        Map<String, String> headers = webHeaders();
        Map<String, String> springboardHeaders = new HashMap<>(headers);
        springboardHeaders.put("access-token", encrypt(school.key,
                accessToken(school.sessionKey, school.userId, school.userType, school.schoolId)));
        springboardHeaders.put("unique-code", school.uniqueCode);
        URI thirdParty = request(URI.create(MCP + "/api/lapp/springboard?yyid=xyrsxt"), springboardHeaders,
                null, "打开浴室服务").redirect(MCP_HOST);
        String lightUrl = parameter(thirdParty.getRawQuery(), "lightUrl");
        if (lightUrl.isEmpty()) lightUrl = CAS_WX;
        if (!"cas.hzxwwl.com".equalsIgnoreCase(checkedUri(redirectUri(thirdParty, lightUrl, "读取浴室入口")).getHost())) {
            throw new BathCode.Failure("浴室入口地址不正确，请稍后重试。");
        }

        URI validation = request(thirdParty, headers, null, "进入学校认证").redirect("cas.hbmzu.edu.cn");
        URI bathCas = request(validation, headers, null, "验证学校会话").redirect("cas.hzxwwl.com");
        String ticketGrant = cookies.getCookieStore().get(URI.create(CAS_SCHOOL)).stream()
                .filter(cookie -> "CASTGC".equals(cookie.getName()) && !cookie.hasExpired())
                .map(HttpCookie::getValue).findFirst().orElse("");
        if (ticketGrant.isEmpty()) throw new BathCode.Failure("学校认证未返回登录票据，请稍后重试。");

        URI schoolLogin = request(bathCas, headers, null, "进入浴室认证").redirect("cas.hbmzu.edu.cn");
        request(schoolLogin, headers, null, "打开学校认证页").requireNavigation();
        Response ticket = request(URI.create(CAS_SCHOOL + "/lyuapServer/v1/tickets/" + urlEncode(ticketGrant)),
                headers("Content-Type", "application/x-www-form-urlencoded", "Accept", "*/*",
                        "Referer", schoolLogin.toString(), "User-Agent", UA_WEB),
                "service=" + urlEncode(lightUrl) + "&loginToken=loginToken", "申请浴室票据");
        ticket.requireSuccess();
        String serviceTicket = ticket.body.trim();
        if (!serviceTicket.startsWith("ST-")) throw new BathCode.Failure("学校认证未签发浴室票据，请稍后重试。");

        headers.put("Referer", CAS_SCHOOL + "/");
        request(URI.create(CAS_WX + "?ticket=" + urlEncode(serviceTicket)), headers,
                null, "验证浴室票据").requireNavigation();
        Response entry = request(URI.create(CAS_WX), headers, null, "进入浴室服务");
        String path = bathPath(entry.source, entry.redirectLocation());
        if (path.isEmpty() || ".".equals(path) || "..".equals(path)) {
            throw new BathCode.Failure("浴室入口缺少登录参数，请稍后重试。");
        }

        URI gatewayLogin = URI.create(GATEWAY + "/api/auth/cas/login/" + urlEncode(path)
                + "?schoolId=" + SCHOOL_ID + "&schoolCode=1043&principal=" + urlEncode(account)
                + "&path=" + urlEncode(path) + "&schoolName=&schoolCampusId=191&schoolCampusName=");
        JSONObject gatewayResponse = request(gatewayLogin, gatewayHeaders(), "", "登录浴室网关").json();
        JSONObject data = gatewayResponse.optJSONObject("data");
        String token = data == null ? "" : field(data, "token");
        if (!gatewayResponse.optBoolean("success") || token.isEmpty()) {
            throw new BathCode.Failure(message(gatewayResponse, "浴室网关登录失败，请稍后重试。"));
        }
        return token;
    }

    BathCode readCode(String token, boolean renew) throws Exception {
        Map<String, String> headers = gatewayHeaders();
        headers.put("REQ-NPD-TOKEN", token);
        Map<String, String> readHeaders = new HashMap<>(headers);
        readHeaders.put("Cache-Control", "no-cache");
        readHeaders.put("Pragma", "no-cache");
        return BathCode.obtain(new BathCode.Gateway() {
            @Override public BathCode read() throws Exception {
                URI url = URI.create(BATH_ACCOUNT + "getUsekeyAndCashMinBalance?_t=" + System.nanoTime());
                JSONObject data = bathResponse(request(url, readHeaders, null, "读取洗澡码"), "");
                if (data == null) {
                    throw new BathCode.Failure("浴室服务未返回使用码状态，请稍后重试。");
                }
                String flag = field(data, "enableUseKeyAccessFlag");
                boolean enabled;
                if ("true".equalsIgnoreCase(flag) || "1".equals(flag)) enabled = true;
                else if ("false".equalsIgnoreCase(flag) || "0".equals(flag)) enabled = false;
                else throw new BathCode.Failure("浴室服务返回的使用码开关状态无法识别。");
                return new BathCode(field(data, "useKey"), field(data, "useKeyDate"),
                        enabled, field(data, "cashAccountMinBalance"));
            }

            @Override public void toggle(String minimumBalance) throws Exception {
                bathResponse(request(URI.create(BATH_ACCOUNT + "swtichUseKeyAccessFlag"), headers,
                        "", "切换洗澡码开关"), minimumBalance);
            }
        }, renew);
    }

    private static JSONObject bathResponse(Response response, String minimumBalance) throws BathCode.Failure {
        if (response.status == 401) throw new SessionExpired();
        JSONObject json = response.status >= 400 ? response.optionalJson() : response.json();
        String fallback = response.action + "失败，请稍后重试。";
        if (json == null) {
            response.requireSuccess();
            throw new BathCode.Failure(fallback);
        }
        if ("5001".equals(json.optString("code"))) throw new SessionExpired();
        if (response.status < 300 && json.optBoolean("success")) return json.optJSONObject("data");
        String error = message(json, response.status >= 400 ? response.httpError() : fallback);
        if (!minimumBalance.isEmpty() && (error.contains("余额不足") || error.contains("余额不够") || error.contains("请充值"))) {
            error += "\n最低余额要求为 " + minimumBalance + " 元。";
        }
        throw new BathCode.Failure(error);
    }

    static long tokenExpiry(String token) {
        try {
            String payload = token.split("\\.")[1];
            return new JSONObject(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8)).getLong("exp");
        } catch (JSONException | IllegalArgumentException | IndexOutOfBoundsException exception) {
            return 0;
        }
    }

    private Response request(URI uri, Map<String, String> headers, String body, String action) throws BathCode.Failure {
        checkedUri(uri);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(12_000);
            for (Map.Entry<String, String> field : headers.entrySet()) {
                connection.setRequestProperty(field.getKey(), field.getValue());
            }
            for (var field : cookies.get(uri, Collections.emptyMap()).entrySet()) {
                connection.setRequestProperty(field.getKey(), String.join("; ", field.getValue()));
            }
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream stream = connection.getOutputStream()) {
                    stream.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            cookies.put(uri, connection.getHeaderFields());
            if (status == 418 || status == 429) {
                throw new BathCode.Failure(action + "时被服务端限制（HTTP " + status + "），请稍后重试。");
            }
            String location = connection.getHeaderField("Location");
            String content = "";
            if (status >= 200 && status < 300) {
                try (InputStream stream = connection.getInputStream()) {
                    content = readBody(stream);
                }
            } else if (status >= 400) {
                String type = connection.getContentType();
                if (type != null && type.toLowerCase(Locale.ROOT).contains("json")) {
                    try (InputStream stream = connection.getErrorStream()) {
                        if (stream != null) content = readBody(stream);
                    }
                }
            }
            return new Response(uri, status, location, content, action);
        } catch (java.net.SocketTimeoutException exception) {
            throw new BathCode.Failure(action + "超时，请检查网络后重试。");
        } catch (IOException exception) {
            throw new BathCode.Failure(action + "时连接失败，请检查网络后重试。");
        } catch (IllegalArgumentException exception) {
            throw new BathCode.Failure(action + "时遇到格式异常的请求参数。");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readBody(InputStream stream) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            if (bytes.size() + count > 2_000_000) throw new IOException("Response too large");
            bytes.write(buffer, 0, count);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static URI checkedUri(URI uri) throws BathCode.Failure {
        String host = uri.getHost();
        String scheme = uri.getScheme();
        int port = uri.getPort();
        boolean allowed = false;
        if (host != null) {
            switch (host.toLowerCase(Locale.ROOT)) {
                case MCP_HOST:
                    allowed = ("http".equals(scheme) || "https".equals(scheme)) && port == 8090;
                    break;
                case "cas.hbmzu.edu.cn":
                case "cas.hzxwwl.com":
                case "gateway.hzxwwl.com":
                case "wx.hzxwwl.com":
                    allowed = "https".equals(scheme) && (port == -1 || port == 443);
                    break;
            }
        }
        if (!allowed || uri.getRawUserInfo() != null) throw new BathCode.Failure("服务端返回了无法访问的登录地址。");
        return uri;
    }

    private static Map<String, String> webHeaders() {
        return headers("User-Agent", UA_WEB, "X-Requested-With", X_REQUESTED_WITH,
                "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language", "zh-CN,zh;q=0.9", "Upgrade-Insecure-Requests", "1");
    }

    private static Map<String, String> gatewayHeaders() {
        return headers("User-Agent", UA_WEB, "X-Requested-With", X_REQUESTED_WITH,
                "Accept", "application/json, text/plain, */*", "Origin", WX_ORIGIN, "Referer", WX_ORIGIN + "/",
                "Accept-Language", "zh-CN,zh;q=0.9");
    }

    private static Map<String, String> headers(String... pairs) {
        Map<String, String> headers = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) headers.put(pairs[i], pairs[i + 1]);
        return headers;
    }

    private static JSONObject accessToken(String session, String id, String type, String school) throws JSONException {
        return new JSONObject().put("clientVersion", CLIENT_VERSION).put("sessionKey", session)
                .put("userId", id).put("userType", type).put("xxdm", school);
    }

    @SuppressLint("GetInstance") // 校园登录接口使用 AES/ECB/PKCS5Padding 编码报文。
    private static String encrypt(byte[] key, JSONObject value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        return encode(cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8)));
    }

    @SuppressLint("GetInstance")
    private static String decrypt(byte[] key, String value, String action) throws BathCode.Failure {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            return new String(cipher.doFinal(decode(value)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new BathCode.Failure(action + "时无法解密服务端响应，请稍后重试。");
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] decode(String value) {
        return Base64.getDecoder().decode(value.replaceAll("\\s", ""));
    }

    private static String deviceId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        StringBuilder id = new StringBuilder();
        for (byte value : bytes) id.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return id.toString();
    }

    private static String urlEncode(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static String parameter(String query, String name) throws IOException {
        if (query == null) return "";
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && name.equals(pair[0])) return URLDecoder.decode(pair[1], "UTF-8");
        }
        return "";
    }

    static URI redirectUri(URI source, String location, String action) throws BathCode.Failure {
        Matcher illegal = UNESCAPED_URL.matcher(location.trim());
        StringBuffer escaped = new StringBuffer();
        while (illegal.find()) {
            illegal.appendReplacement(escaped, String.format(Locale.ROOT, "%%%02X", (int) illegal.group().charAt(0)));
        }
        illegal.appendTail(escaped);
        try {
            return URI.create(source.resolve(escaped.toString()).toASCIIString());
        } catch (IllegalArgumentException exception) {
            throw new BathCode.Failure(action + "时收到格式异常的登录跳转地址。");
        }
    }

    /** 从浴室前端跳转的路由参数中读取网关路径。 */
    static String bathPath(URI source, String location) throws BathCode.Failure {
        try {
            URL entry = new URL(source.toURL(), location.trim());
            int port = entry.getPort();
            if (!"https".equalsIgnoreCase(entry.getProtocol()) || !"wx.hzxwwl.com".equalsIgnoreCase(entry.getHost())
                    || entry.getUserInfo() != null || (port != -1 && port != 443)) {
                throw new BathCode.Failure("浴室服务返回了无法访问的入口地址。");
            }
            String fragment = entry.getRef();
            if (fragment != null) {
                int login = fragment.indexOf("/login?");
                if (login >= 0) {
                    int start = login + "/login?".length();
                    int end = fragment.indexOf('#', start);
                    String path = parameter(fragment.substring(start, end < 0 ? fragment.length() : end), "path");
                    if (!path.isEmpty()) return path;
                }
            }
            return parameter(entry.getQuery(), "path");
        } catch (MalformedURLException | IllegalArgumentException exception) {
            throw new BathCode.Failure("无法从浴室入口地址读取登录参数。");
        } catch (IOException exception) {
            throw new BathCode.Failure("无法解码浴室入口的登录参数。");
        }
    }

    private static String field(JSONObject json, String name) {
        return json.isNull(name) ? "" : json.optString(name);
    }

    private static String message(JSONObject json, String fallback) {
        String text = field(json, "msg").trim();
        if (text.isEmpty()) text = field(json, "message").trim();
        return text.isEmpty() || text.length() > 180 || text.indexOf('\uFFFD') >= 0 ? fallback : text;
    }

    static final class SignInRequired extends BathCode.Failure {
        SignInRequired(String message) {
            super(message);
        }
    }

    static final class SessionExpired extends BathCode.Failure {
        SessionExpired() {
            super("浴室会话已失效，请重试。");
        }
    }

    static final class SchoolSession {
        final byte[] key;
        final String uniqueCode, sessionKey, userId, userType, schoolId;

        SchoolSession(byte[] key, String uniqueCode, String sessionKey, String userId, String userType, String schoolId) {
            this.key = key;
            this.uniqueCode = uniqueCode;
            this.sessionKey = sessionKey;
            this.userId = userId;
            this.userType = userType;
            this.schoolId = schoolId;
        }
    }

    private static final class Response {
        final URI source;
        final int status;
        final String location;
        final String body;
        final String action;

        Response(URI source, int status, String location, String body, String action) {
            this.source = source;
            this.status = status;
            this.location = location;
            this.body = body;
            this.action = action;
        }

        URI redirect(String host) throws BathCode.Failure {
            URI target = checkedUri(redirectUri(source, redirectLocation(), action));
            if (host.equalsIgnoreCase(target.getHost())) return target;
            throw new BathCode.Failure(action + "时收到了非预期的登录跳转地址。");
        }

        String redirectLocation() throws BathCode.Failure {
            if (status >= 300 && status < 400 && location != null && !location.trim().isEmpty()) return location;
            throw new BathCode.Failure(action + "时未完成登录跳转（HTTP " + status + "）。");
        }

        void requireNavigation() throws BathCode.Failure {
            if (status >= 300 && status < 400 && location != null) return;
            requireSuccess();
        }

        void requireSuccess() throws BathCode.Failure {
            if (status < 200 || status >= 300) throw new BathCode.Failure(httpError());
        }

        String httpError() {
            return action + "失败（HTTP " + status + "）。";
        }

        JSONObject json() throws BathCode.Failure {
            requireSuccess();
            JSONObject json = optionalJson();
            if (json == null) throw new BathCode.Failure(action + "时收到无法识别的服务响应，请稍后重试。");
            return json;
        }

        JSONObject optionalJson() {
            try {
                return new JSONObject(body);
            } catch (JSONException exception) {
                return null;
            }
        }

        String requiredData(JSONObject json) throws BathCode.Failure {
            Object data = json.opt("data");
            if (data instanceof String && !((String) data).isEmpty()) return (String) data;
            throw new BathCode.Failure(message(json, action + "时缺少必要的服务响应数据，请稍后重试。"));
        }
    }
}
