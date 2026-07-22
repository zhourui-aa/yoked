package org.example.bot;

import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金融计算器工具 —— 复利、房贷、税率、汇率转换
 * 供 Function Calling 调用
 */
public class CalculatorTool {

    /** 工具名称（Function Calling 注册用） */
    public static final String NAME = "financial_calculator";

    /** 工具描述（AI 根据此描述判断是否调用） */
    public static final String DESCRIPTION =
            "金融计算器，支持以下四种计算：\n" +
                    "1. compound_interest — 复利计算：输入本金、年化利率、年限，返回最终本息和\n" +
                    "2. mortgage — 房贷计算：输入贷款总额、年利率、年限，返回月供、总利息\n" +
                    "3. tax — 个税计算：输入税前月薪，返回税后收入、个税金额\n" +
                    "4. exchange — 汇率转换：输入金额、源货币、目标货币，返回转换后金额\n" +
                    "当用户涉及理财、房贷、工资扣税、外币换算时调用此工具。";

    /** 执行入口 */
    public String execute(JsonObject args) {
        String type = args.has("calc_type") ? args.get("calc_type").getAsString() : "";

        return switch (type) {
            case "compound_interest" -> compoundInterest(args);
            case "mortgage" -> mortgage(args);
            case "tax" -> tax(args);
            case "exchange" -> exchange(args);
            default -> "❌ 未知的计算类型，支持：compound_interest、mortgage、tax、exchange";
        };
    }

    // ==================== 1. 复利计算 ====================

    private String compoundInterest(JsonObject args) {
        try {
            BigDecimal principal = new BigDecimal(getString(args, "principal"));
            BigDecimal rate = new BigDecimal(getString(args, "annual_rate")).divide(new BigDecimal("100"));
            int years = Integer.parseInt(getString(args, "years"));
            int timesPerYear = args.has("times_per_year") ? Integer.parseInt(getString(args, "times_per_year")) : 1;

            // 复利公式：A = P * (1 + r/n)^(n*t)
            BigDecimal base = BigDecimal.ONE.add(rate.divide(new BigDecimal(timesPerYear), 10, RoundingMode.HALF_UP));
            BigDecimal exponent = base.pow(timesPerYear * years);
            BigDecimal amount = principal.multiply(exponent).setScale(2, RoundingMode.HALF_UP);
            BigDecimal interest = amount.subtract(principal).setScale(2, RoundingMode.HALF_UP);

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
                    principal, rate.multiply(new BigDecimal("100")), years, timesPerYear,
                    amount, interest,
                    amount.divide(principal, 2, RoundingMode.HALF_UP)
            );
        } catch (Exception e) {
            return "❌ 复利计算失败：" + e.getMessage();
        }
    }

    // ==================== 2. 房贷计算 ====================

    private String mortgage(JsonObject args) {
        try {
            BigDecimal loan = new BigDecimal(getString(args, "loan_amount"));
            BigDecimal annualRate = new BigDecimal(getString(args, "annual_rate")).divide(new BigDecimal("100"));
            int years = Integer.parseInt(getString(args, "years"));
            String method = args.has("method") ? getString(args, "method") : "equal_interest"; // 默认等额本息

            int months = years * 12;
            BigDecimal monthlyRate = annualRate.divide(new BigDecimal("12"), 10, RoundingMode.HALF_UP);

            if ("equal_interest".equals(method)) {
                // 等额本息：月供 = [P * r * (1+r)^n] / [(1+r)^n - 1]
                BigDecimal onePlusR = BigDecimal.ONE.add(monthlyRate);
                BigDecimal numerator = loan.multiply(monthlyRate).multiply(onePlusR.pow(months));
                BigDecimal denominator = onePlusR.pow(months).subtract(BigDecimal.ONE);
                BigDecimal monthlyPayment = numerator.divide(denominator, 2, RoundingMode.HALF_UP);

                BigDecimal totalPayment = monthlyPayment.multiply(new BigDecimal(months)).setScale(2, RoundingMode.HALF_UP);
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
                        loan, annualRate.multiply(new BigDecimal("100")), years, months,
                        monthlyPayment, totalPayment, totalInterest,
                        totalInterest.divide(loan, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                );
            } else {
                // 等额本金：每月递减
                BigDecimal monthlyPrincipal = loan.divide(new BigDecimal(months), 2, RoundingMode.HALF_UP);
                BigDecimal firstMonthInterest = loan.multiply(monthlyRate).setScale(2, RoundingMode.HALF_UP);
                BigDecimal firstMonthPayment = monthlyPrincipal.add(firstMonthInterest);

                BigDecimal totalInterest = BigDecimal.ZERO;
                for (int i = 0; i < months; i++) {
                    BigDecimal remaining = loan.subtract(monthlyPrincipal.multiply(new BigDecimal(i)));
                    totalInterest = totalInterest.add(remaining.multiply(monthlyRate)).setScale(2, RoundingMode.HALF_UP);
                }

                BigDecimal totalPayment = loan.add(totalInterest);

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
                        loan, annualRate.multiply(new BigDecimal("100")), years, months,
                        firstMonthPayment, monthlyPrincipal.multiply(monthlyRate).setScale(2, RoundingMode.HALF_UP),
                        totalPayment, totalInterest
                );
            }
        } catch (Exception e) {
            return "❌ 房贷计算失败：" + e.getMessage();
        }
    }

    // ==================== 3. 个税计算 ====================

    private String tax(JsonObject args) {
        try {
            BigDecimal monthlySalary = new BigDecimal(getString(args, "monthly_salary"));
            BigDecimal socialInsurance = args.has("social_insurance")
                    ? new BigDecimal(getString(args, "social_insurance"))
                    : monthlySalary.multiply(new BigDecimal("0.105")); // 默认五险一金 10.5%
            BigDecimal specialDeduction = args.has("special_deduction")
                    ? new BigDecimal(getString(args, "special_deduction"))
                    : BigDecimal.ZERO;

            // 起征点 5000
            BigDecimal threshold = new BigDecimal("5000");
            BigDecimal taxableIncome = monthlySalary.subtract(socialInsurance).subtract(specialDeduction).subtract(threshold);

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
                        monthlySalary, socialInsurance, specialDeduction,
                        monthlySalary.subtract(socialInsurance)
                );
            }

            // 2024 年个税累进税率表（月度）
            BigDecimal[] brackets = {
                    new BigDecimal("3000"), new BigDecimal("12000"),
                    new BigDecimal("25000"), new BigDecimal("35000"),
                    new BigDecimal("55000"), new BigDecimal("80000")
            };
            BigDecimal[] rates = {
                    new BigDecimal("0.03"), new BigDecimal("0.10"),
                    new BigDecimal("0.20"), new BigDecimal("0.25"),
                    new BigDecimal("0.30"), new BigDecimal("0.35"),
                    new BigDecimal("0.45")
            };
            BigDecimal[] quickDeductions = {
                    new BigDecimal("0"), new BigDecimal("210"),
                    new BigDecimal("1410"), new BigDecimal("2660"),
                    new BigDecimal("4410"), new BigDecimal("7160"),
                    new BigDecimal("15160")
            };

            BigDecimal tax = BigDecimal.ZERO;
            int level = 0;
            for (int i = 0; i < brackets.length; i++) {
                if (taxableIncome.compareTo(brackets[i]) <= 0) {
                    level = i;
                    break;
                }
                level = i + 1;
            }

            tax = taxableIncome.multiply(rates[level]).subtract(quickDeductions[level]).setScale(2, RoundingMode.HALF_UP);
            if (tax.compareTo(BigDecimal.ZERO) < 0) tax = BigDecimal.ZERO;

            BigDecimal afterTax = monthlySalary.subtract(socialInsurance).subtract(tax);

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
                    monthlySalary, socialInsurance, specialDeduction, taxableIncome,
                    rates[level].multiply(new BigDecimal("100")), quickDeductions[level],
                    tax, afterTax,
                    afterTax.divide(monthlySalary, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
            );
        } catch (Exception e) {
            return "❌ 个税计算失败：" + e.getMessage();
        }
    }

    // ==================== 4. 汇率转换 ====================

    private String exchange(JsonObject args) {
        try {
            BigDecimal amount = new BigDecimal(getString(args, "amount"));
            String from = getString(args, "from_currency").toUpperCase();
            String to = getString(args, "to_currency").toUpperCase();

            // 内置常用汇率（相对 USD），实际项目应调用实时 API
            java.util.Map<String, BigDecimal> rates = new java.util.HashMap<>();
            rates.put("USD", new BigDecimal("1"));
            rates.put("CNY", new BigDecimal("7.25"));
            rates.put("EUR", new BigDecimal("0.92"));
            rates.put("JPY", new BigDecimal("151.5"));
            rates.put("GBP", new BigDecimal("0.79"));
            rates.put("HKD", new BigDecimal("7.82"));
            rates.put("KRW", new BigDecimal("1350"));
            rates.put("AUD", new BigDecimal("1.52"));
            rates.put("CAD", new BigDecimal("1.36"));
            rates.put("SGD", new BigDecimal("1.35"));

            if (!rates.containsKey(from) || !rates.containsKey(to)) {
                return "❌ 暂不支持该货币对。支持：" + String.join("、", rates.keySet());
            }

            // 先转 USD，再转目标货币
            BigDecimal usdAmount = amount.divide(rates.get(from), 10, RoundingMode.HALF_UP);
            BigDecimal result = usdAmount.multiply(rates.get(to)).setScale(2, RoundingMode.HALF_UP);

            return String.format(
                    "💱 汇率转换结果\n" +
                            "━━━━━━━━━━━━━━━\n" +
                            "• 金额：%,.2f %s\n" +
                            "• 汇率：1 %s ≈ %.4f %s\n" +
                            "• 转换结果：%,.2f %s\n" +
                            "━━━━━━━━━━━━━━━\n" +
                            "💡 汇率仅供参考，实际以银行成交价为准",
                    amount, from,
                    from, rates.get(to).divide(rates.get(from), 4, RoundingMode.HALF_UP), to,
                    result, to
            );
        } catch (Exception e) {
            return "❌ 汇率转换失败：" + e.getMessage();
        }
    }

    // ==================== 辅助方法 ====================

    private static String getString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "0";
    }

    /** 返回 Function Calling 的 JSON Schema */
    public static java.util.Map<String, Object> getParametersSchema() {
        java.util.Map<String, Object> schema = new java.util.HashMap<>();

        schema.put("calc_type", java.util.Map.of(
                "type", "string",
                "description", "计算类型：compound_interest（复利）、mortgage（房贷）、tax（个税）、exchange（汇率）",
                "enum", java.util.List.of("compound_interest", "mortgage", "tax", "exchange")
        ));
        schema.put("principal", java.util.Map.of("type", "string", "description", "本金/贷款总额/税前月薪/转换金额"));
        schema.put("annual_rate", java.util.Map.of("type", "string", "description", "年利率（百分比，如 5 表示 5%）"));
        schema.put("years", java.util.Map.of("type", "string", "description", "年限（复利投资年限/房贷年限）"));
        schema.put("monthly_salary", java.util.Map.of("type", "string", "description", "税前月薪（个税用）"));
        schema.put("from_currency", java.util.Map.of("type", "string", "description", "源货币代码：CNY、USD、EUR、JPY 等"));
        schema.put("to_currency", java.util.Map.of("type", "string", "description", "目标货币代码"));
        schema.put("amount", java.util.Map.of("type", "string", "description", "转换金额"));
        schema.put("method", java.util.Map.of(
                "type", "string",
                "description", "房贷还款方式：equal_interest（等额本息，默认）、equal_principal（等额本金）"
        ));
        schema.put("times_per_year", java.util.Map.of("type", "string", "description", "年复利次数（默认1，按月复利填12）"));
        schema.put("social_insurance", java.util.Map.of("type", "string", "description", "五险一金金额（个税用，默认按10.5%估算）"));
        schema.put("special_deduction", java.util.Map.of("type", "string", "description", "专项附加扣除金额（个税用）"));

        return schema;
    }
}