package org.example.bot.impl;

import org.example.bot.service.CalculatorService;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * 金融计算器服务实现 — 复利、房贷、个税、实时汇率。
 *
 * <p>个税使用 2024 年累进税率表；汇率调用 exchangerate-api.com 免费 API（带 5 分钟缓存）。
 */
public class CalculatorServiceImpl implements CalculatorService {

    // ---- 汇率缓存 ----
    private static String cachedCurrencyData = null;
    private static long cacheTime = 0;
    private static final long CACHE_TTL = 5 * 60 * 1000;

    public CalculatorServiceImpl() {
        System.out.println("[计算器] 金融计算服务已就绪（复利/房贷/个税/汇率）");
    }

    // ==================== 1. 复利计算 ====================

    @Override
    public String compoundInterest(double principal, double annualRate, int years, int timesPerYear) {
        BigDecimal p = BigDecimal.valueOf(principal);
        BigDecimal rate = BigDecimal.valueOf(annualRate).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
        int t = timesPerYear > 0 ? timesPerYear : 1;

        BigDecimal base = BigDecimal.ONE.add(rate.divide(BigDecimal.valueOf(t), 10, RoundingMode.HALF_UP));
        BigDecimal amount = p.multiply(base.pow(t * years)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal interest = amount.subtract(p).setScale(2, RoundingMode.HALF_UP);

        return String.format(
                "💰 复利计算结果\n" +
                "━━━━━━━━━━━━━━━\n" +
                "• 本金：%,.2f 元\n" +
                "• 年化利率：%.2f%%\n" +
                "• 投资年限：%d 年\n" +
                "• 年复利次数：%d 次\n" +
                "• 最终本息和：%,.2f 元\n" +
                "• 累计利息：%,.2f 元\n" +
                "━━━━━━━━━━━━━━━\n" +
                "💡 相当于本金翻了 %.2f 倍",
                p, annualRate, years, t, amount, interest,
                amount.divide(p, 2, RoundingMode.HALF_UP));
    }

    // ==================== 2. 房贷计算 ====================

    @Override
    public String mortgage(double loanAmount, double annualRate, int years, String method) {
        BigDecimal loan = BigDecimal.valueOf(loanAmount);
        BigDecimal rate = BigDecimal.valueOf(annualRate).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
        int months = years * 12;
        BigDecimal monthlyRate = rate.divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_UP);

        if ("equal_principal".equals(method)) {
            return mortgageEqualPrincipal(loan, rate, years, months, monthlyRate);
        }
        return mortgageEqualInterest(loan, rate, years, months, monthlyRate);
    }

    private String mortgageEqualInterest(BigDecimal loan, BigDecimal rate, int years,
                                         int months, BigDecimal monthlyRate) {
        BigDecimal onePlusR = BigDecimal.ONE.add(monthlyRate);
        BigDecimal numerator = loan.multiply(monthlyRate).multiply(onePlusR.pow(months));
        BigDecimal denominator = onePlusR.pow(months).subtract(BigDecimal.ONE);
        BigDecimal monthlyPayment = numerator.divide(denominator, 2, RoundingMode.HALF_UP);
        BigDecimal totalPayment = monthlyPayment.multiply(BigDecimal.valueOf(months)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalInterest = totalPayment.subtract(loan).setScale(2, RoundingMode.HALF_UP);

        return String.format(
                "🏠 房贷计算结果（等额本息）\n" +
                "━━━━━━━━━━━━━━━\n" +
                "• 贷款总额：%,.2f 元\n" +
                "• 年利率：%.2f%%\n" +
                "• 贷款年限：%d 年（%d 期）\n" +
                "• 月供：%,.2f 元\n" +
                "• 还款总额：%,.2f 元\n" +
                "• 总利息：%,.2f 元\n" +
                "━━━━━━━━━━━━━━━\n" +
                "💡 利息占贷款总额的 %.1f%%",
                loan, rate.multiply(BigDecimal.valueOf(100)), years, months,
                monthlyPayment, totalPayment, totalInterest,
                totalInterest.divide(loan, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)));
    }

    private String mortgageEqualPrincipal(BigDecimal loan, BigDecimal rate, int years,
                                          int months, BigDecimal monthlyRate) {
        BigDecimal monthlyPrincipal = loan.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
        BigDecimal firstMonthInterest = loan.multiply(monthlyRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal firstMonthPayment = monthlyPrincipal.add(firstMonthInterest);

        BigDecimal totalInterest = BigDecimal.ZERO;
        for (int i = 0; i < months; i++) {
            BigDecimal remaining = loan.subtract(monthlyPrincipal.multiply(BigDecimal.valueOf(i)));
            totalInterest = totalInterest.add(remaining.multiply(monthlyRate)).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal totalPayment = loan.add(totalInterest);
        BigDecimal monthlyDecrease = monthlyPrincipal.multiply(monthlyRate).setScale(2, RoundingMode.HALF_UP);

        return String.format(
                "🏠 房贷计算结果（等额本金）\n" +
                "━━━━━━━━━━━━━━━\n" +
                "• 贷款总额：%,.2f 元\n" +
                "• 年利率：%.2f%%\n" +
                "• 贷款年限：%d 年（%d 期）\n" +
                "• 首月月供：%,.2f 元（每月递减约 %.2f 元）\n" +
                "• 还款总额：%,.2f 元\n" +
                "• 总利息：%,.2f 元\n" +
                "━━━━━━━━━━━━━━━\n" +
                "💡 等额本金前期压力大，但总利息更少",
                loan, rate.multiply(BigDecimal.valueOf(100)), years, months,
                firstMonthPayment, monthlyDecrease, totalPayment, totalInterest);
    }

    // ==================== 3. 个税计算 ====================

    @Override
    public String calculateTax(double monthlySalary, double socialInsurance, double specialDeduction) {
        BigDecimal salary = BigDecimal.valueOf(monthlySalary);
        BigDecimal insurance = socialInsurance > 0
                ? BigDecimal.valueOf(socialInsurance)
                : salary.multiply(BigDecimal.valueOf(0.105)); // 默认五险一金 10.5%
        BigDecimal deduction = BigDecimal.valueOf(specialDeduction);
        BigDecimal threshold = BigDecimal.valueOf(5000);

        BigDecimal taxableIncome = salary.subtract(insurance).subtract(deduction).subtract(threshold);

        if (taxableIncome.compareTo(BigDecimal.ZERO) <= 0) {
            return String.format(
                    "💼 个税计算结果\n" +
                    "━━━━━━━━━━━━━━━\n" +
                    "• 税前月薪：%,.2f 元\n" +
                    "• 五险一金：%,.2f 元\n" +
                    "• 专项扣除：%,.2f 元\n" +
                    "• 应纳税所得额：0 元（未达起征点）\n" +
                    "• 个税：0 元\n" +
                    "• 税后收入：%,.2f 元\n" +
                    "━━━━━━━━━━━━━━━\n" +
                    "💡 你的收入未达到个税起征点（5000元）",
                    salary, insurance, deduction, salary.subtract(insurance));
        }

        // 2024 年个税累进税率表（月度）
        BigDecimal[] brackets = {
                BigDecimal.valueOf(3000), BigDecimal.valueOf(12000),
                BigDecimal.valueOf(25000), BigDecimal.valueOf(35000),
                BigDecimal.valueOf(55000), BigDecimal.valueOf(80000)
        };
        BigDecimal[] rates = {
                BigDecimal.valueOf(0.03), BigDecimal.valueOf(0.10),
                BigDecimal.valueOf(0.20), BigDecimal.valueOf(0.25),
                BigDecimal.valueOf(0.30), BigDecimal.valueOf(0.35),
                BigDecimal.valueOf(0.45)
        };
        BigDecimal[] quickDeductions = {
                BigDecimal.valueOf(0), BigDecimal.valueOf(210),
                BigDecimal.valueOf(1410), BigDecimal.valueOf(2660),
                BigDecimal.valueOf(4410), BigDecimal.valueOf(7160),
                BigDecimal.valueOf(15160)
        };

        int level = rates.length - 1; // 默认最高档
        for (int i = 0; i < brackets.length; i++) {
            if (taxableIncome.compareTo(brackets[i]) <= 0) {
                level = i;
                break;
            }
        }

        BigDecimal tax = taxableIncome.multiply(rates[level])
                .subtract(quickDeductions[level]).setScale(2, RoundingMode.HALF_UP);
        if (tax.compareTo(BigDecimal.ZERO) < 0) tax = BigDecimal.ZERO;

        BigDecimal afterTax = salary.subtract(insurance).subtract(tax);

        return String.format(
                "💼 个税计算结果\n" +
                "━━━━━━━━━━━━━━━\n" +
                "• 税前月薪：%,.2f 元\n" +
                "• 五险一金：%,.2f 元\n" +
                "• 专项扣除：%,.2f 元\n" +
                "• 应纳税所得额：%,.2f 元\n" +
                "• 适用税率：%.0f%%\n" +
                "• 速算扣除数：%,.2f 元\n" +
                "• 个税：%,.2f 元\n" +
                "• 税后收入：%,.2f 元\n" +
                "━━━━━━━━━━━━━━━\n" +
                "💡 实际到手约为税前工资的 %.1f%%",
                salary, insurance, deduction, taxableIncome,
                rates[level].multiply(BigDecimal.valueOf(100)), quickDeductions[level],
                tax, afterTax,
                afterTax.divide(salary, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)));
    }

    // ==================== 4. 汇率转换 ====================

    @Override
    public String convertCurrency(double amount, String from, String to) {
        from = from.toUpperCase();
        to = to.toUpperCase();

        if (from.equals(to)) {
            return String.format("💱 %.2f %s = %.2f %s（同币种无需转换）", amount, from, amount, to);
        }

        try {
            double rate = getExchangeRate(from, to);
            double converted = BigDecimal.valueOf(amount)
                    .multiply(BigDecimal.valueOf(rate))
                    .setScale(2, RoundingMode.HALF_UP)
                    .doubleValue();

            return String.format(
                    "💱 汇率转换结果\n" +
                    "━━━━━━━━━━━━━━━\n" +
                    "• 金额：%,.2f %s\n" +
                    "• 汇率：1 %s ≈ %.4f %s\n" +
                    "• 转换结果：%,.2f %s\n" +
                    "━━━━━━━━━━━━━━━\n" +
                    "💡 汇率仅供参考，实际以银行成交价为准",
                    amount, from, from, rate, to, converted, to);
        } catch (Exception e) {
            return "汇率转换失败：" + e.getMessage();
        }
    }

    // ---- 实时汇率 API（带缓存）----

    private double getExchangeRate(String from, String to) throws Exception {
        String json = getCachedRates(from);
        String searchKey = "\"" + to + "\":";
        int idx = json.indexOf(searchKey);
        if (idx == -1) {
            throw new Exception("目标币种 " + to + " 不支持，请使用常见货币代码（如 USD、CNY、EUR、JPY）");
        }
        int start = idx + searchKey.length();
        int end = json.indexOf(",", start);
        if (end == -1) end = json.indexOf("}", start);
        String rateStr = json.substring(start, end).trim();
        return Double.parseDouble(rateStr);
    }

    private String getCachedRates(String base) throws Exception {
        long now = System.currentTimeMillis();
        if (cachedCurrencyData != null && now - cacheTime < CACHE_TTL) {
            if (cachedCurrencyData.contains("\"base\":\"" + base + "\"")) {
                return cachedCurrencyData;
            }
        }
        String urlStr = "https://api.exchangerate-api.com/v4/latest/" + base;
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new Exception("汇率 API 请求失败，HTTP " + code);
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
