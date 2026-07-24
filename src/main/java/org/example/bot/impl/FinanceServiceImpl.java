package org.example.bot.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.example.bot.service.FinanceService;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 金融行情服务实现 — 股票/基金/加密货币实时行情。
 *
 * <p>数据源：
 * <ul>
 *   <li>A 股 — 新浪财经（免费，无需 API Key）</li>
 *   <li>基金 — 天天基金（免费，无需 API Key）</li>
 *   <li>加密货币 — Binance（免费，无需 API Key）</li>
 * </ul>
 */
public class FinanceServiceImpl implements FinanceService {

    private static final String SINA_STOCK_API = "https://hq.sinajs.cn/list=";
    private static final String FUND_API = "https://fundgz.1234567.com.cn/js/";
    private static final String BINANCE_API = "https://api.binance.com/api/v3/ticker/24hr?symbol=";

    /** 常见加密货币 → USDT 交易对 */
    private static final java.util.Map<String, String> CRYPTO_MAP = java.util.Map.ofEntries(
        java.util.Map.entry("BTC", "BTCUSDT"),
        java.util.Map.entry("ETH", "ETHUSDT"),
        java.util.Map.entry("DOGE", "DOGEUSDT"),
        java.util.Map.entry("SOL", "SOLUSDT"),
        java.util.Map.entry("XRP", "XRPUSDT"),
        java.util.Map.entry("ADA", "ADAUSDT"),
        java.util.Map.entry("BNB", "BNBUSDT"),
        java.util.Map.entry("AVAX", "AVAXUSDT"),
        java.util.Map.entry("DOT", "DOTUSDT"),
        java.util.Map.entry("MATIC", "MATICUSDT"),
        java.util.Map.entry("LINK", "LINKUSDT"),
        java.util.Map.entry("UNI", "UNIUSDT")
    );

    public FinanceServiceImpl() {
        System.out.println("[金融] 金融行情服务已就绪（股票/基金/加密货币）");
    }

    // ==================== 股票 ====================

    @Override
    public String queryStock(String code) {
        if (code == null || code.trim().length() != 6) {
            return "❌ 股票代码格式错误，请输入6位数字，例如：600036";
        }
        code = code.trim();

        String market = code.startsWith("6") ? "sh" : "sz";
        String url = SINA_STOCK_API + market + code;

        try {
            String result = fetchUrl(url);
            // 新浪 API 返回 CSV 格式：var hq_str_sh600036="名称,今开,昨收,现价,最高,最低,...,成交量,...,成交额,..."
            if (!result.contains("hq_str_")) {
                return "❌ 查询失败，股票代码可能不存在";
            }

            String data = result.split("=")[1].replace("\"", "").trim();
            String[] parts = data.split(",");
            if (parts.length < 10) {
                return "❌ 返回数据不完整，请稍后重试";
            }

            String name = parts[0];
            double open = parseDouble(parts[1], "开盘价");
            double prevClose = parseDouble(parts[2], "昨收");
            double price = parseDouble(parts[3], "现价");
            double high = parseDouble(parts[4], "最高");
            double low = parseDouble(parts[5], "最低");
            long volume = parseLong(parts[8], "成交量");
            long amount = parseLong(parts[9], "成交额");

            double change = price - prevClose;
            double changePercent = prevClose > 0 ? (change / prevClose) * 100 : 0;
            String trend = change >= 0 ? "📈" : "📉";
            String sign = change >= 0 ? "+" : "";

            return String.format(
                "%s %s（%s）\n" +
                "━━━━━━━━━━━━━━━\n" +
                "💰 当前价：%.2f 元\n" +
                "📊 涨跌：%s%.2f（%s%.2f%%）\n" +
                "📌 今开：%.2f | 最高：%.2f | 最低：%.2f\n" +
                "📦 成交量：%,d 手 | 成交额：%,.0f 万元\n" +
                "━━━━━━━━━━━━━━━",
                trend, name, code,
                price,
                sign, change, sign, changePercent,
                open, high, low,
                volume, amount / 10000.0
            );
        } catch (Exception e) {
            return "❌ 股票查询失败：" + e.getMessage();
        }
    }

    // ==================== 基金 ====================

    @Override
    public String queryFund(String code) {
        if (code == null || code.trim().length() != 6) {
            return "❌ 基金代码格式错误，请输入6位数字，例如：000001";
        }
        code = code.trim();

        String url = FUND_API + code + ".js";

        try {
            String raw = fetchUrl(url);
            // 天天基金返回 jsonpgz({...}) 格式，提取 JSON 部分用 Gson 解析
            if (!raw.contains("jsonpgz(")) {
                return "❌ 查询失败，基金代码可能不存在";
            }

            String json = raw.substring(raw.indexOf("(") + 1, raw.lastIndexOf(")"));
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

            String name = obj.has("name") ? obj.get("name").getAsString() : "未知";
            String date = obj.has("jzrq") ? obj.get("jzrq").getAsString() : "";
            double nav = obj.has("dwjz") ? obj.get("dwjz").getAsDouble() : 0;
            double estimate = obj.has("gsz") ? obj.get("gsz").getAsDouble() : 0;
            double changePct = obj.has("gszzl") ? obj.get("gszzl").getAsDouble() : 0;
            String time = obj.has("gztime") ? obj.get("gztime").getAsString() : "";

            String trend = changePct >= 0 ? "📈" : "📉";
            String sign = changePct >= 0 ? "+" : "";

            return String.format(
                "%s %s（%s）\n" +
                "━━━━━━━━━━━━━━━\n" +
                "📌 最新净值：%.4f（%s）\n" +
                "📊 实时估值：%.4f\n" +
                "📈 估值涨跌：%s%.2f%%\n" +
                "⏰ 估值时间：%s\n" +
                "━━━━━━━━━━━━━━━\n" +
                "💡 净值是官方公布值，估值是实时估算，仅供参考",
                trend, name, code,
                nav, date,
                estimate,
                sign, changePct,
                time
            );
        } catch (Exception e) {
            return "❌ 基金查询失败：" + e.getMessage();
        }
    }

    // ==================== 加密货币 ====================

    @Override
    public String queryCrypto(String symbol) {
        if (symbol == null || symbol.trim().isEmpty()) {
            return "❌ 请输入加密货币符号，例如：BTC、ETH、DOGE";
        }
        symbol = symbol.trim().toUpperCase();

        String tradingPair = CRYPTO_MAP.getOrDefault(symbol, symbol + "USDT");
        String url = BINANCE_API + tradingPair;

        try {
            String result = fetchUrl(url);
            JsonObject obj = JsonParser.parseString(result).getAsJsonObject();

            // Binance 可能返回错误（如交易对不存在）
            if (obj.has("code")) {
                return "❌ 加密货币「" + symbol + "」不支持，请使用主流币种如 BTC、ETH、DOGE、SOL";
            }

            String pair = obj.has("symbol") ? obj.get("symbol").getAsString() : symbol;
            double price = obj.has("lastPrice") ? obj.get("lastPrice").getAsDouble() : 0;
            double open = obj.has("openPrice") ? obj.get("openPrice").getAsDouble() : 0;
            double high = obj.has("highPrice") ? obj.get("highPrice").getAsDouble() : 0;
            double low = obj.has("lowPrice") ? obj.get("lowPrice").getAsDouble() : 0;
            double volume = obj.has("volume") ? obj.get("volume").getAsDouble() : 0;
            double quoteVol = obj.has("quoteVolume") ? obj.get("quoteVolume").getAsDouble() : 0;
            double change = obj.has("priceChange") ? obj.get("priceChange").getAsDouble() : 0;
            double changePct = obj.has("priceChangePercent") ? obj.get("priceChangePercent").getAsDouble() : 0;

            String trend = change >= 0 ? "📈" : "📉";
            String sign = change >= 0 ? "+" : "";

            return String.format(
                "%s %s\n" +
                "━━━━━━━━━━━━━━━\n" +
                "💰 当前价：$%,.2f\n" +
                "📊 24h涨跌：%s%.2f（%s%.2f%%）\n" +
                "📌 开盘：$%,.2f | 最高：$%,.2f | 最低：$%,.2f\n" +
                "📦 24h成交量：%,.2f | 成交额：$%,.0f\n" +
                "━━━━━━━━━━━━━━━",
                trend, pair,
                price,
                sign, change, sign, changePct,
                open, high, low,
                volume, quoteVol
            );
        } catch (Exception e) {
            return "❌ 加密货币查询失败：" + e.getMessage()
                + "（支持的币种：BTC、ETH、DOGE、SOL、XRP、ADA、BNB、AVAX、DOT、MATIC、LINK、UNI）";
        }
    }

    // ==================== 工具方法 ====================

    private String fetchUrl(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private double parseDouble(String value, String field) {
        try {
            return Double.parseDouble(value);
        } catch (Exception e) {
            System.err.println("[金融] ⚠ 解析数值失败 field=" + field + " value=" + value);
            return 0;
        }
    }

    private long parseLong(String value, String field) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            System.err.println("[金融] ⚠ 解析数值失败 field=" + field + " value=" + value);
            return 0;
        }
    }
}
