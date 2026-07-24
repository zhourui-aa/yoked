package org.example.bot.service;

/**
 * 成语接龙游戏服务接口。
 *
 * <p>场景：群内团建或闲暇时，机器人当裁判或参与游戏。
 * 规则：说出的成语首字必须与上一个成语的尾字相同，且不能重复。
 */
public interface IdiomService {

    /**
     * 开始新一局成语接龙，机器人先出题
     * @param userId 用户标识
     * @return 游戏开始信息
     */
    String startGame(String userId);

    /**
     * 玩家接龙，机器人验证并回复
     * @param userId 用户标识
     * @param idiom 玩家说的成语
     * @return 接龙结果，机器人可能会接下一个
     */
    String play(String userId, String idiom);

    /**
     * 查看当前游戏状态
     * @param userId 用户标识
     * @return 状态信息
     */
    String getState(String userId);

    /**
     * 玩家认输，机器人给出正确答案
     * @param userId 用户标识
     * @return 结束信息
     */
    String giveUp(String userId);
}