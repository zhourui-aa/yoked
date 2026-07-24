package org.example.bot.service;

/**
 * 金融行情服务接口 — 股票、基金、加密货币实时行情查询。
 */
public interface FinanceService {

  /**
   * 查询 A 股股票实时行情。
   *
   * @param code 股票代码（如 600036、000001、sh600036、sz000001）
   * @return 格式化的股票行情信息
   */
  String queryStock(String code);

  /**
   * 查询基金实时估值净值。
   *
   * @param code 基金代码（如 000001、110011）
   * @return 格式化的基金净值信息
   */
  String queryFund(String code);

  /**
   * 查询加密货币实时价格。
   *
   * @param symbol 币种代码（如 BTC、ETH、DOGE）
   * @return 格式化的加密货币价格信息
   */
  String queryCrypto(String symbol);
}