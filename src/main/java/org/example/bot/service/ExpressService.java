package org.example.bot.service;

/**
 * 快递查询服务接口 — 基于快递鸟 API。
 */
public interface ExpressService {

    /**
     * 查询快递物流轨迹。
     *
     * @param trackingNumber 快递单号
     * @param company        快递公司名称或编码，可为空（自动识别）
     * @param phone          收/寄件人手机号后四位，顺丰必填
     * @return 格式化的物流轨迹信息
     */
    String query(String trackingNumber, String company, String phone);
}
