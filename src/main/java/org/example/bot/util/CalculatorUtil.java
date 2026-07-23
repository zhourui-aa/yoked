package org.example.bot.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DecimalFormat;

/**
 * 计算器工具类 — 提供复利、房贷、税率、汇率等计算。
 */
public class CalculatorUtil {

    private static final DecimalFormat DF = new DecimalFormat("#,##0.00");

    // 汇率缓存（简单内存缓存，5分钟过期）
    private static String cachedCurrencyData = null;
    private static long cacheTime = 0;
    private static final long CACHE_TTL = 5 * 60 * 1000;

    /**
     * 复利终值计算
     * @param principal 本金
     * @param annualRate 年利率（如 5% 传入 5）
     * @param years 投资年限
     * @param timesPerYear 每年复利次数
     * @return 终值及收益描述
     */
    public static String compoundInterest(double principal, double annualRate, int years, int timesPerYear) {
        double rate = annualRate / 100.0;
        double amount = principal * Math.pow(1 + rate / timesPerYear, (long) timesPerYear * years);
        double profit = amount - principal;
        return String.format(
                "本金 %.2f，年利率 %.2f%%，复利 %d 次/年，投资 %d 年后：\n终值 = %s\n收益 = %s",
                principal, annualRate, timesPerYear, years,
                DF.format(amount), DF.format(profit)
        );
    }

    /**
     * 等额本息房贷月供计算
     * @param principal 贷款总额
     * @param annualRate 年利率（如 4.9% 传入 4.9）
     * @param years 贷款年限
     * @return 月供、总利息、总还款额
     */
    public static String mortgagePayment(double principal, double annualRate, int years) {
        double monthlyRate = annualRate / 100.0 / 12;
        int months = years * 12;
        double payment = principal * monthlyRate * Math.pow(1 + monthlyRate, months)
                / (Math.pow(1 + monthlyRate, months) - 1);
        double totalPayment = payment * months;
        double totalInterest = totalPayment - principal;
        return String.format(
                "贷款 %.2f 万元，年利率 %.2f%%，期限 %d 年：\n月供 = %s 元\n总还款 = %s 元\n总利息 = %s 元",
                principal / 10000, annualRate, years,
                DF.format(payment), DF.format(totalPayment), DF.format(totalInterest)
        );
    }

    /**
     * 简单税率计算（所得税模拟）
     * @param income 收入
     * @param rate 税率（如 20% 传入 20）
     * @return 纳税额及税后收入
     */
    public static String calculateTax(double income, double rate) {
        double tax = income * rate / 100.0;
        double afterTax = income - tax;
        return String.format(
                "税前收入 %.2f，税率 %.2f%%：\n应纳税额 = %s\n税后收入 = %s",
                income, rate, DF.format(tax), DF.format(afterTax)
        );
    }

    /**
     * 汇率转换（使用免费API：exchangerate-api.com，无需注册即可每日有限次调用）
     * @param amount 金额
     * @param fromCurrency 源货币（如 USD）
     * @param toCurrency 目标货币（如 CNY）
     * @return 转换结果
     */
    public static String convertCurrency(double amount, String fromCurrency, String toCurrency) {
        fromCurrency = fromCurrency.toUpperCase();
        toCurrency = toCurrency.toUpperCase();
        try {
            double rate = getExchangeRate(fromCurrency, toCurrency);
            double converted = amount * rate;
            return String.format(
                    "%.2f %s = %.2f %s（汇率 1 %s = %.4f %s）",
                    amount, fromCurrency, converted, toCurrency, fromCurrency, rate, toCurrency
            );
        } catch (Exception e) {
            return "汇率转换失败：" + e.getMessage();
        }
    }

    // 获取实时汇率（带缓存）
    private static double getExchangeRate(String from, String to) throws Exception {
        // 如果相同币种，返回1
        if (from.equals(to)) return 1.0;

        // 尝试从缓存获取
        String json = getCachedRates(from);
        // 简单解析（依赖 fastjson 或 Gson，这里用字符串截取，避免额外依赖）
        // 实际建议用 Gson 或 Jackson，项目已有 Gson 依赖
        String searchKey = "\"" + to + "\":";
        int idx = json.indexOf(searchKey);
        if (idx == -1) {
            throw new Exception("目标币种 " + to + " 不支持");
        }
        int start = idx + searchKey.length();
        int end = json.indexOf(",", start);
        if (end == -1) end = json.indexOf("}", start);
        String rateStr = json.substring(start, end).trim();
        return Double.parseDouble(rateStr);
    }

    private static String getCachedRates(String base) throws Exception {
        long now = System.currentTimeMillis();
        if (cachedCurrencyData != null && now - cacheTime < CACHE_TTL) {
            // 检查缓存中的基准货币是否一致（简单处理，若不一致则刷新）
            if (cachedCurrencyData.contains("\"base\":\"" + base + "\"")) {
                return cachedCurrencyData;
            }
        }
        // 请求新数据
        String urlStr = "https://api.exchangerate-api.com/v4/latest/" + base;
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new Exception("API 请求失败，HTTP " + code);
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        cachedCurrencyData = sb.toString();
        cacheTime = now;
        return cachedCurrencyData;
    }
}