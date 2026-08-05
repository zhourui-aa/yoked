package org.example.bot.service;

/**
 * 垃圾分类查询服务接口。
 *
 * <p>场景：用户不知道某样垃圾属于哪一类，输入物品名称，返回分类结果和投放建议。
 * 覆盖上海/北京等城市通用的四分类标准：可回收物、有害垃圾、湿垃圾、干垃圾。
 */
public interface GarbageService {

    /**
     * 查询物品的垃圾分类
     * @param itemName 物品名称，如"电池"、"苹果核"、"塑料袋"
     * @return 分类结果，包含类别、投放建议
     */
    String classify(String itemName);
}