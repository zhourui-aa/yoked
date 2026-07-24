package org.example.bot.impl;

import org.example.bot.service.FinanceService;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 金融行情服务实现 — 基于新浪财经、天天基金、币安公开 API。
 * 全部免费，无需 API Key。
 */
public class FinanceServiceImpl implements FinanceService {

    private static final DecimalFormat DF = new DecimalFormat("#,##0.00");
    private static final int TIMEOUT = 8000;

    private static final String SINA_URL = "http://hq.sinajs.cn/list=";
    private static final String FUND_URL = "http://fundgz.1234567.com.cn/js/";
    private static final String CRYPTO_URL = "https://api.binance.com/api/v3/ticker/price?symbol=";

    // 股票名称缓存
    private static final java.util.Map<String, String> STOCK_NAME_CACHE = new java.util.HashMap<>();

    public FinanceServiceImpl() {
        System.out.println("[金融] 📈 金融行情服务已就绪（股票/基金/加密货币）");
    }

    // ==================== 股票 ====================

    @Override
    public String queryStock(String code) {
        String normalized = normalizeStockCode(code);
        if (normalized == null) {
            return "股票代码格式不正确，请提供 6 位数字代码，如 600036（招商银行）、000001（平安银行）。";
        }
        try {
            String raw = httpGet(SINA_URL + normalized, "UTF-8");
            String body = extractSinaBody(raw);
            if (body == null || body.isBlank()) {
                return "未查询到股票「" + code + "」的信息，请检查代码是否正确。";
            }
            String[] fields = body.split(",");
            if (fields.length < 32) {
                return "股票「" + code + "」数据不完整，可能是非交易时段，请稍后再试。";
            }

            String name = fields[0].trim();
            String open = fields[1];       // 今日开盘
            String prevClose = fields[2];  // 昨日收盘
            String price = fields[3];      // 当前价
            String high = fields[4];       // 今日最高
            String low = fields[5];        // 今日最低
            String volume = fields[8];     // 成交量（手）
            String date = fields[30];      // 日期
            String time = fields[31];      // 时间

            double current = Double.parseDouble(price);
            double yesterday = Double.parseDouble(prevClose);
            double change = current - yesterday;
            double changePercent = (change / yesterday) * 100;
            String arrow = change >= 0 ? "📈" : "📉";
            String sign = change >= 0 ? "+" : "";

            // 缓存股票名称
            STOCK_NAME_CACHE.put(normalized, name);

            long vol = 0;
            try { vol = Long.parseLong(volume); } catch (Exception ignored) {}

            StringBuilder sb = new StringBuilder();
            sb.append(arrow).append(" ").append(name).append("（").append(code).append("）\n");
            sb.append("━━━━━━━━━━━━━━━━━━\n");
            sb.append("💰 当前价：").append(DF.format(current)).append(" 元\n");
            sb.append(String.format("📊 涨跌额：%s%s  涨幅：%s%.2f%%\n", sign, DF.format(change), sign, changePercent));
            sb.append("🔺 最高：").append(DF.format(Double.parseDouble(high)))
              .append("  🔻 最低：").append(DF.format(Double.parseDouble(low))).append("\n");
            sb.append("📅 昨收：").append(DF.format(yesterday))
              .append("  今开：").append(DF.format(Double.parseDouble(open))).append("\n");
            if (vol > 0) {
                sb.append("📦 成交量：").append(vol / 10000.0 > 1
                    ? DF.format(vol / 10000.0) + " 万手"
                    : vol + " 手").append("\n");
            }
            sb.append("🕐 更新时间：").append(date).append(" ").append(time);
            return sb.toString();
        } catch (Exception e) {
            return "股票查询失败：" + e.getMessage();
        }
    }

    // ==================== 基金 ====================

    @Override
    public String queryFund(String code) {
        String normalized = normalizeFundCode(code);
        if (normalized == null) {
            return "基金代码格式不正确，请提供 6 位数字代码，如 000001（华夏成长）、110011（易方达中小盘）。";
        }
        try {
            String raw = httpGet(FUND_URL + normalized + ".js", "UTF-8");
            // 格式：jsonpgz({"fundcode":"000001","name":"...","jzrq":"...","dwjz":"...","gsz":"...","gszzl":"...","gztime":"..."});
            String json = extractJson(raw);
            if (json == null) {
                return "未查询到基金「" + code + "」的信息，请检查代码是否正确。";
            }
            String name = extractField(json, "name");
            String jzrq = extractField(json, "jzrq");      // 净值日期
            String dwjz = extractField(json, "dwjz");      // 单位净值
            String gsz = extractField(json, "gsz");        // 估算值
            String gszzl = extractField(json, "gszzl");    // 估算涨幅
            String gztime = extractField(json, "gztime");  // 估值时间

            StringBuilder sb = new StringBuilder();
            sb.append("💼 ").append(name != null ? name : "基金").append("（").append(code).append("）\n");
            sb.append("━━━━━━━━━━━━━━━━━━\n");
            if (dwjz != null) {
                sb.append("📋 单位净值：").append(DF.format(Double.parseDouble(dwjz))).append(" 元\n");
            }
            if (jzrq != null) {
                sb.append("📅 净值日期：").append(jzrq).append("\n");
            }
            if (gsz != null) {
                sb.append("📊 实时估值：").append(DF.format(Double.parseDouble(gsz))).append(" 元\n");
            }
            if (gszzl != null) {
                double zl = Double.parseDouble(gszzl);
                String arrow = zl >= 0 ? "📈" : "📉";
                String sign = zl >= 0 ? "+" : "";
                sb.append(arrow).append(" 估算涨幅：").append(sign).append(DF.format(zl)).append("%\n");
            }
            if (gztime != null) {
                sb.append("🕐 估值时间：").append(gztime);
            }
            return sb.toString();
        } catch (Exception e) {
            return "基金查询失败：" + e.getMessage();
        }
    }

    // ==================== 加密货币 ====================

    @Override
    public String queryCrypto(String symbol) {
        String normalized = normalizeCryptoSymbol(symbol);
        if (normalized == null) {
            return "币种代码不正确，请提供如 BTC、ETH、DOGE、BNB、SOL、XRP 等。";
        }
        try {
            String pair = normalized + "USDT";
            String raw = httpGet(CRYPTO_URL + pair, "UTF-8");
            // 格式：{"symbol":"BTCUSDT","price":"123456.78"}
            String price = extractField(raw, "price");
            if (price == null) {
                return "未查询到「" + symbol + "」的价格，请检查币种代码。";
            }

            double p = Double.parseDouble(price);
            StringBuilder sb = new StringBuilder();
            sb.append("🪙 ").append(normalized).append("/USDT\n");
            sb.append("━━━━━━━━━━━━━━━━━━\n");
            sb.append("💰 当前价格：$").append(p >= 1 ? DF.format(p) : String.format("%.6f", p)).append("\n");

            // 尝试获取 24h 涨跌幅
            try {
                String tickerUrl = "https://api.binance.com/api/v3/ticker/24hr?symbol=" + pair;
                String tickerRaw = httpGet(tickerUrl, "UTF-8");
                String changePercent = extractField(tickerRaw, "priceChangePercent");
                String high = extractField(tickerRaw, "highPrice");
                String low = extractField(tickerRaw, "lowPrice");
                if (changePercent != null) {
                    double cp = Double.parseDouble(changePercent);
                    String arrow = cp >= 0 ? "📈" : "📉";
                    String sign = cp >= 0 ? "+" : "";
                    sb.append(arrow).append(" 24h涨跌：").append(sign).append(cp).append("%\n");
                }
                if (high != null) {
                    double h = Double.parseDouble(high);
                    sb.append("🔺 24h最高：$").append(h >= 1 ? DF.format(h) : String.format("%.6f", h)).append("\n");
                }
                if (low != null) {
                    double l = Double.parseDouble(low);
                    sb.append("🔻 24h最低：$").append(l >= 1 ? DF.format(l) : String.format("%.6f", l)).append("\n");
                }
            } catch (Exception ignored) {
                // 24h 数据获取失败不影响主流程
            }

            sb.append("🕐 数据来源：Binance 实时行情");
            return sb.toString();
        } catch (Exception e) {
            return "加密货币查询失败：" + e.getMessage();
        }
    }

    // ==================== 工具方法 ====================

    private String httpGet(String urlStr, String charset) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; YokedBot/1.0)");
        // 新浪 API 需要 Referer
        if (urlStr.contains("sina")) {
            conn.setRequestProperty("Referer", "https://finance.sina.com.cn");
        }
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new Exception("HTTP " + code);
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), charset != null
                    ? java.nio.charset.Charset.forName(charset) : StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    /** 从新浪 API 响应中提取数据部分 */
    private String extractSinaBody(String raw) {
        // 格式：var hq_str_sh600036="xxxx";
        int start = raw.indexOf('"');
        int end = raw.lastIndexOf('"');
        if (start == -1 || end == -1 || start >= end) return null;
        return raw.substring(start + 1, end);
    }

    /** 从天天基金 API 响应中提取 JSON */
    private String extractJson(String raw) {
        // 格式：jsonpgz({...});
        int start = raw.indexOf('(');
        int end = raw.lastIndexOf(')');
        if (start == -1 || end == -1 || start >= end) return null;
        return raw.substring(start + 1, end);
    }

    /** 简单 JSON 字段提取（避免额外依赖） */
    private String extractField(String json, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(json);
        if (m.find()) return m.group(1);
        // 数字字段
        p = Pattern.compile("\"" + key + "\"\\s*:\\s*([0-9.\\-eE]+)");
        m = p.matcher(json);
        if (m.find()) return m.group(1);
        return null;
    }

    /** 标准化股票代码 */
    private String normalizeStockCode(String code) {
        if (code == null || code.isBlank()) return null;
        String c = code.strip().toLowerCase();
        // 去掉 sh/sz 前缀
        if (c.startsWith("sh") || c.startsWith("sz")) {
            c = c.substring(2);
        }
        if (!c.matches("\\d{6}")) return null;
        // 判断交易所：6开头=上海，0/3开头=深圳
        if (c.startsWith("6")) return "sh" + c;
        return "sz" + c;
    }

    /** 标准化基金代码 */
    private String normalizeFundCode(String code) {
        if (code == null || code.isBlank()) return null;
        String c = code.strip();
        if (!c.matches("\\d{6}")) return null;
        return c;
    }

    /** 标准化加密货币代码 */
    private String normalizeCryptoSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        return symbol.strip().toUpperCase();
    }
}