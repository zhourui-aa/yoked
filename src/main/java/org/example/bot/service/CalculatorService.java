package org.example.bot.service;

/**
 * 金融计算器服务接口 — 复利、房贷、个税、汇率转换。
 */
public interface CalculatorService {

    /**
     * 复利终值计算。
     *
     * @param principal    本金
     * @param annualRate   年利率（百分比，如 5 表示 5%）
     * @param years        投资年限
     * @param timesPerYear 每年复利次数（默认 1，按月复利填 12）
     * @return 格式化的终值及收益描述
     */
    String compoundInterest(double principal, double annualRate, int years, int timesPerYear);

    /**
     * 房贷月供计算（等额本息或等额本金）。
     *
     * @param loanAmount 贷款总额（元）
     * @param annualRate 年利率（百分比，如 4.9 表示 4.9%）
     * @param years      贷款年限
     * @param method     还款方式：equal_interest（等额本息，默认）或 equal_principal（等额本金）
     * @return 格式化的月供、总利息、总还款额
     */
    String mortgage(double loanAmount, double annualRate, int years, String method);

    /**
     * 个税计算（2024年累进税率表）。
     *
     * @param monthlySalary    税前月薪（元）
     * @param socialInsurance  五险一金金额（传 0 则按 10.5% 估算）
     * @param specialDeduction 专项附加扣除金额
     * @return 格式化的纳税额及税后收入
     */
    String calculateTax(double monthlySalary, double socialInsurance, double specialDeduction);

    /**
     * 汇率转换。
     *
     * @param amount 金额
     * @param from   源货币代码（如 USD、CNY、EUR）
     * @param to     目标货币代码
     * @return 格式化的转换结果
     */
    String convertCurrency(double amount, String from, String to);
}
