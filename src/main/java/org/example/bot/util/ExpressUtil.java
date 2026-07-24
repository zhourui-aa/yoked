package org.example.bot.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 快递查询工具类 — 基于快递鸟 API。
 *
 * <p>配置项：
 * <ul>
 *   <li>{@code kdniao.ebusiness.id} / {@code KDNIAO_EBUSINESS_ID} — 商户 ID</li>
 *   <li>{@code kdniao.app.key} / {@code KDNIAO_APP_KEY} — API Key</li>
 *   <li>{@code kdniao.request.type} / {@code KDNIAO_REQUEST_TYPE} — 查询指令，默认 8001</li>
 * </ul>
 */
public final class ExpressUtil {

    private static final String API_URL = "https://api.kdniao.com/Ebusiness/EbusinessOrderHandle.aspx";
    private static final String REQUEST_TYPE_DETECT = "2002";
    private static final String DEFAULT_REQUEST_TYPE = "8001";
    private static final int MAX_TRACE_LINES = 8;

    private static final Map<String, String> COMPANY_ALIASES = buildCompanyAliases();
    private static final Map<String, String> COMPANY_NAMES = buildCompanyNames();

    private ExpressUtil() {}

    /**
     * 查询快递物流（自动识别快递公司）
     */
    public static String query(String trackingNumber) {
        return query(trackingNumber, null, null);
    }

    /**
     * 查询快递物流
     *
     * @param trackingNumber 快递单号
     * @param company        快递公司名称或编码，可为空（自动识别）
     * @param phone          收/寄件人手机号后四位，顺丰必填
     */
    public static String query(String trackingNumber, String company, String phone) {
        if (!isConfigured()) {
            return "快递查询服务未配置，请在 config.properties 中设置 kdniao.ebusiness.id 和 kdniao.app.key。";
        }

        String num = normalizeTrackingNumber(trackingNumber);
        if (num == null) {
            return "快递单号格式不正确，请提供 6~32 位字母或数字。";
        }

        try {
            String shipperCode = resolveCompanyCode(company);
            if (shipperCode == null) {
                shipperCode = autoDetectCompany(num);
            }
            if (shipperCode == null || shipperCode.isBlank()) {
                return "无法识别快递公司，请补充说明，例如：顺丰、圆通、中通。";
            }

            String json = queryTrack(shipperCode, num, phone);
            return formatResponse(json, shipperCode, num);
        } catch (Exception e) {
            return "快递查询失败：" + e.getMessage();
        }
    }

    /** 将中文快递公司名转为快递鸟编码（大写） */
    public static String resolveCompanyCode(String company) {
        if (company == null || company.isBlank()) {
            return null;
        }
        String normalized = company.strip().toLowerCase();
        String mapped = COMPANY_ALIASES.get(normalized);
        if (mapped != null) {
            return mapped;
        }
        if (normalized.matches("[a-z0-9]+")) {
            return normalized.toUpperCase();
        }
        for (Map.Entry<String, String> entry : COMPANY_ALIASES.entrySet()) {
            if (normalized.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean isConfigured() {
        return getConfig("kdniao.ebusiness.id", "KDNIAO_EBUSINESS_ID") != null
                && getConfig("kdniao.app.key", "KDNIAO_APP_KEY") != null;
    }

    private static String getConfig(String propertyKey, String envKey) {
        String value = ConfigUtil.get(propertyKey, envKey);
        if (value == null || value.isBlank() || value.startsWith("请在此填入")) {
            return null;
        }
        return value.strip();
    }

    private static String getRequestType() {
        String type = getConfig("kdniao.request.type", "KDNIAO_REQUEST_TYPE");
        return type != null ? type : DEFAULT_REQUEST_TYPE;
    }

    private static String normalizeTrackingNumber(String trackingNumber) {
        if (trackingNumber == null) {
            return null;
        }
        String num = trackingNumber.replaceAll("\\s+", "").strip();
        if (!num.matches("^[A-Za-z0-9-]{6,32}$")) {
            return null;
        }
        return num;
    }

    private static String autoDetectCompany(String num) throws Exception {
        JsonObject request = new JsonObject();
        request.addProperty("LogisticCode", num);
        String json = invokeApi(REQUEST_TYPE_DETECT, request);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        if (!root.has("Success") || !root.get("Success").getAsBoolean()) {
            return null;
        }
        if (!root.has("Shippers") || !root.get("Shippers").isJsonArray()) {
            return null;
        }
        JsonArray shippers = root.getAsJsonArray("Shippers");
        if (shippers.isEmpty()) {
            return null;
        }
        return shippers.get(0).getAsJsonObject().get("ShipperCode").getAsString();
    }

    private static String queryTrack(String shipperCode, String num, String phone) throws Exception {
        JsonObject request = new JsonObject();
        request.addProperty("OrderCode", "");
        request.addProperty("ShipperCode", shipperCode);
        request.addProperty("LogisticCode", num);
        if (phone != null && !phone.isBlank()) {
            request.addProperty("CustomerName", phone.strip());
        } else if ("SF".equals(shipperCode)) {
            request.addProperty("CustomerName", "");
        }
        return invokeApi(getRequestType(), request);
    }

    private static String invokeApi(String requestType, JsonObject requestData) throws Exception {
        String ebusinessId = getConfig("kdniao.ebusiness.id", "KDNIAO_EBUSINESS_ID");
        String appKey = getConfig("kdniao.app.key", "KDNIAO_APP_KEY");
        String requestJson = requestData.toString();

        byte[] md5 = MessageDigest.getInstance("MD5")
                .digest((requestJson + appKey).getBytes(StandardCharsets.UTF_8));
        String dataSign = URLEncoder.encode(
                Base64.getEncoder().encodeToString(md5), StandardCharsets.UTF_8);
        String encodedRequestData = URLEncoder.encode(requestJson, StandardCharsets.UTF_8);

        String body = "RequestType=" + URLEncoder.encode(requestType, StandardCharsets.UTF_8)
                + "&EBusinessID=" + URLEncoder.encode(ebusinessId, StandardCharsets.UTF_8)
                + "&RequestData=" + encodedRequestData
                + "&DataSign=" + dataSign
                + "&DataType=2";

        HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(10000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; YokedBot/1.0)");
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(conn);
    }

    private static String formatResponse(String json, String shipperCode, String num) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        boolean success = root.has("Success") && root.get("Success").getAsBoolean();
        String reason = root.has("Reason") && !root.get("Reason").isJsonNull()
                ? root.get("Reason").getAsString() : "";

        if (!success) {
            if ("SF".equals(shipperCode) && (reason.isBlank() || reason.contains("手机号"))) {
                return "快递查询失败：" + (reason.isBlank() ? "查询失败" : reason)
                        + "\n提示：顺丰快递需要提供手机号后四位。";
            }
            return "快递查询失败：" + (reason.isBlank() ? "未找到物流信息，请检查单号和快递公司。" : reason);
        }

        JsonArray traces = root.has("Traces") && root.get("Traces").isJsonArray()
                ? root.getAsJsonArray("Traces") : new JsonArray();
        if (traces.isEmpty()) {
            String msg = reason.isBlank() ? "暂无轨迹信息" : reason;
            return "暂未查询到「" + num + "」的物流轨迹：" + msg;
        }

        String companyName = COMPANY_NAMES.getOrDefault(shipperCode, shipperCode);
        String state = root.has("State") ? String.valueOf(root.get("State").getAsInt()) : "";
        StringBuilder sb = new StringBuilder();
        sb.append("📦 快递查询 — ").append(companyName).append(" (").append(num).append(")\n");
        if (!state.isBlank()) {
            sb.append("状态：").append(stateText(state)).append("\n\n");
        }

        List<JsonObject> items = new ArrayList<>();
        traces.forEach(el -> items.add(el.getAsJsonObject()));
        int limit = Math.min(items.size(), MAX_TRACE_LINES);
        for (int i = items.size() - 1; i >= items.size() - limit; i--) {
            JsonObject item = items.get(i);
            String time = item.has("AcceptTime") ? item.get("AcceptTime").getAsString() : "";
            String station = item.has("AcceptStation") ? item.get("AcceptStation").getAsString() : "";
            sb.append("• ").append(time);
            if (!station.isBlank()) {
                sb.append(" — ").append(station.strip());
            }
            sb.append("\n");
        }

        if (items.size() > MAX_TRACE_LINES) {
            sb.append("\n（仅展示最近 ").append(MAX_TRACE_LINES).append(" 条轨迹）");
        }
        return sb.toString().strip();
    }

    private static String stateText(String state) {
        return switch (state) {
            case "0" -> "暂无轨迹";
            case "1" -> "已揽收";
            case "2" -> "在途中";
            case "3" -> "已签收";
            case "4" -> "问题件";
            case "5" -> "转寄";
            default -> "运输中";
        };
    }

    private static String readResponse(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        var stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (stream == null) {
            throw new IOException("HTTP " + code);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            if (code >= 400) {
                throw new IOException("HTTP " + code + "：" + sb);
            }
            return sb.toString();
        }
    }

    private static Map<String, String> buildCompanyAliases() {
        Map<String, String> map = new LinkedHashMap<>();
        putCompany(map, "SF", "顺丰", "顺丰速运", "sf", "shunfeng");
        putCompany(map, "YTO", "圆通", "圆通速递", "yt", "yuantong");
        putCompany(map, "ZTO", "中通", "中通快递", "zt", "zhongtong");
        putCompany(map, "YD", "韵达", "韵达快递", "yd", "yunda");
        putCompany(map, "STO", "申通", "申通快递", "sto", "shentong");
        putCompany(map, "JTSD", "极兔", "极兔速递", "jt", "jtexpress");
        putCompany(map, "JD", "京东", "京东物流", "京东快递");
        putCompany(map, "EMS", "邮政", "中国邮政", "ems");
        putCompany(map, "YZPY", "邮政包裹", "邮政国内", "youzhengguonei");
        putCompany(map, "DBL", "德邦", "德邦快递", "db", "debangwuliu");
        putCompany(map, "HTKY", "百世", "百世快递", "ht", "huitongkuaidi");
        putCompany(map, "ZJS", "宅急送", "zhaijisong");
        putCompany(map, "FWX", "丰网", "fengwang");
        putCompany(map, "DNWL", "丹鸟", "danniao");
        return Map.copyOf(map);
    }

    private static Map<String, String> buildCompanyNames() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("SF", "顺丰速运");
        map.put("YTO", "圆通速递");
        map.put("ZTO", "中通快递");
        map.put("YD", "韵达快递");
        map.put("STO", "申通快递");
        map.put("JTSD", "极兔速递");
        map.put("JD", "京东物流");
        map.put("EMS", "EMS");
        map.put("YZPY", "邮政包裹");
        map.put("DBL", "德邦快递");
        map.put("HTKY", "百世快递");
        map.put("ZJS", "宅急送");
        map.put("FWX", "丰网速运");
        map.put("DNWL", "丹鸟");
        return Map.copyOf(map);
    }

    private static void putCompany(Map<String, String> map, String code, String... aliases) {
        map.put(code.toLowerCase(), code);
        for (String alias : aliases) {
            map.put(alias.toLowerCase(), code);
        }
    }
}
