package org.example.bot.impl;

import org.example.bot.service.FinanceService;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 金融行情服务实现 - 使用免费 API 查询股票/基金/加密货币行情
 */
public class FinanceServiceImpl implements FinanceService {

    private static final String SINA_STOCK_API = "https://hq.sinajs.cn/list=";
    private static final String FUND_API = "https://fundgz.1234567.com.cn/js/";
    private static final String BINANCE_API = "https://api.binance.com/api/v3/ticker/24hr?symbol=";

    @Override
    public String queryStock(String code) {
        if (code == null || code.trim().length() != 6) {
            return "❌ 股票代码格式错误，请输入6位数字，例如：600036";
        }
        code = code.trim();
        
        // 根据代码判断交易所：60开头为上海，其余为深圳
        String market = code.startsWith("6") ? "sh" : "sz";
        String url = SINA_STOCK_API + market + code;
        
        try {
            String result = fetchUrl(url);
            // 新浪API返回格式：var hq_str_sh600036="招商银行,35.23,35.18,35.25,35.40,35.10,35.23,35.24,..."
            if (result.contains("hq_str_")) {
                String data = result.split("=")[1].replace("\"", "").trim();
                String[] parts = data.split(",");
                if (parts.length >= 11) {
                    String name = parts[0];
                    double open = parseDouble(parts[1]);
                    double prevClose = parseDouble(parts[2]);
                    double price = parseDouble(parts[3]);
                    double high = parseDouble(parts[4]);
                    double low = parseDouble(parts[5]);
                    double change = price - prevClose;
                    double changePercent = prevClose > 0 ? (change / prevClose) * 100 : 0;
                    long volume = parseLong(parts[8]);
                    long amount = parseLong(parts[9]);
                    
                    String trend = change >= 0 ? "📈" : "📉";
                    String color = change >= 0 ? "\u001B[31m" : "\u001B[32m";
                    String reset = "\u001B[0m";
                    
                    return String.format(
                        "%s %s\n" +
                        "📊 当前价：%.2f 元\n" +
                        "📈 涨跌幅：%s%.2f%%%s（%.2f元）\n" +
                        "📌 开盘：%.2f | 最高：%.2f | 最低：%.2f\n" +
                        "📦 成交量：%,d 手 | 成交额：%,d 万元",
                        trend, name, price, color, changePercent, reset, change,
                        open, high, low, volume, amount / 10000
                    );
                }
            }
            return "❌ 查询失败，股票代码可能不存在";
        } catch (Exception e) {
            return "❌ 股票查询失败：" + e.getMessage();
        }
    }

    @Override
    public String queryFund(String code) {
        if (code == null || code.trim().length() != 6) {
            return "❌ 基金代码格式错误，请输入6位数字，例如：000001";
        }
        code = code.trim();
        
        String url = FUND_API + code + ".js";
        
        try {
            String result = fetchUrl(url);
            // 天天基金API返回格式：jsonpgz({"fundcode":"000001","name":"华夏成长","jzrq":"2024-01-15","dwjz":"1.2345","gsz":"1.2456","gszzl":"0.90","gztime":"2024-01-15 15:00"});
            if (result.contains("jsonpgz(")) {
                String json = result.substring(result.indexOf("(") + 1, result.lastIndexOf(")"));
                // 简单解析JSON
                String name = extractValue(json, "name");
                String date = extractValue(json, "jzrq");
                double nav = parseDouble(extractValue(json, "dwjz"));
                double estimate = parseDouble(extractValue(json, "gsz"));
                double estimateChange = parseDouble(extractValue(json, "gszzl"));
                String time = extractValue(json, "gztime");
                
                String trend = estimateChange >= 0 ? "📈" : "📉";
                String color = estimateChange >= 0 ? "\u001B[31m" : "\u001B[32m";
                String reset = "\u001B[0m";
                
                return String.format(
                    "%s %s（%s）\n" +
                    "📌 最新净值：%.4f\n" +
                    "📊 实时估值：%.4f\n" +
                    "📈 估值涨跌：%s%.2f%%%s\n" +
                    "⏰ 更新时间：%s",
                    trend, name, code, nav, estimate, color, estimateChange, reset, time
                );
            }
            return "❌ 查询失败，基金代码可能不存在";
        } catch (Exception e) {
            return "❌ 基金查询失败：" + e.getMessage();
        }
    }

    @Override
    public String queryCrypto(String symbol) {
        if (symbol == null || symbol.trim().isEmpty()) {
            return "❌ 请输入加密货币符号，例如：BTC、ETH、DOGE";
        }
        symbol = symbol.trim().toUpperCase();
        
        // 确保是有效的交易对格式
        String tradingPair;
        if (symbol.equals("BTC")) {
            tradingPair = "BTCUSDT";
        } else if (symbol.equals("ETH")) {
            tradingPair = "ETHUSDT";
        } else if (symbol.equals("DOGE")) {
            tradingPair = "DOGEUSDT";
        } else if (symbol.equals("SOL")) {
            tradingPair = "SOLUSDT";
        } else if (symbol.equals("XRP")) {
            tradingPair = "XRPUSDT";
        } else {
            // 默认尝试 USDT 交易对
            tradingPair = symbol + "USDT";
        }
        
        String url = BINANCE_API + tradingPair;
        
        try {
            String result = fetchUrl(url);
            // Binance API 返回 JSON
            String symbolName = extractValue(result, "symbol");
            double price = parseDouble(extractValue(result, "lastPrice"));
            double openPrice = parseDouble(extractValue(result, "openPrice"));
            double high = parseDouble(extractValue(result, "highPrice"));
            double low = parseDouble(extractValue(result, "lowPrice"));
            double volume = parseDouble(extractValue(result, "volume"));
            double quoteVolume = parseDouble(extractValue(result, "quoteVolume"));
            double change = parseDouble(extractValue(result, "priceChange"));
            double changePercent = parseDouble(extractValue(result, "priceChangePercent"));
            
            String trend = change >= 0 ? "📈" : "📉";
            String color = change >= 0 ? "\u001B[31m" : "\u001B[32m";
            String reset = "\u001B[0m";
            
            return String.format(
                "%s %s\n" +
                "💰 当前价：$%,.2f\n" +
                "📈 24h涨跌幅：%s%.2f%%%s（$%.2f）\n" +
                "📌 开盘：$%,.2f | 最高：$%,.2f | 最低：$%,.2f\n" +
                "📦 24h成交量：%,.2f %s | 成交额：$%,.2f",
                trend, symbolName, price, color, changePercent, reset, change,
                openPrice, high, low, volume, symbol, quoteVolume
            );
        } catch (Exception e) {
            return "❌ 加密货币查询失败：" + e.getMessage() + "（请确认币种是否支持，支持：BTC、ETH、DOGE、SOL、XRP 等）";
        }
    }

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

    private double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (Exception e) {
            return 0;
        }
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return 0;
        }
    }

    private String extractValue(String json, String key) {
        // 简单的 JSON 解析
        String pattern = "\"" + key + "\":\"";
        int start = json.indexOf(pattern);
        if (start == -1) {
            // 尝试数字格式
            pattern = "\"" + key + "\":";
            start = json.indexOf(pattern);
            if (start == -1) return "";
            start += pattern.length();
            int end = json.indexOf(",", start);
            if (end == -1) end = json.indexOf("}", start);
            return json.substring(start, end).trim();
        }
        start += pattern.length();
        int end = json.indexOf("\"", start);
        if (end == -1) return "";
        return json.substring(start, end);
    }
}
