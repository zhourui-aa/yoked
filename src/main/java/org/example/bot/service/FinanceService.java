package org.example.bot.service;

/**
 * 金融行情服务接口 - 股票/基金/加密货币实时行情查询
 */
public interface FinanceService {

    /**
     * 查询 A 股股票实时行情
     * @param code 股票代码（6位数字）
     * @return 行情信息字符串
     */
    String queryStock(String code);

    /**
     * 查询基金净值估值
     * @param code 基金代码（6位数字）
     * @return 基金信息字符串
     */
    String queryFund(String code);

    /**
     * 查询加密货币实时价格
     * @param symbol 加密货币符号（如 BTC、ETH、DOGE）
     * @return 价格信息字符串
     */
    String queryCrypto(String symbol);
}
