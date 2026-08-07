package org.example.bot;

import org.example.bot.service.*;
import org.example.bot.impl.WeatherServiceImpl;
import org.example.bot.tools.ToolCenter;
import org.example.bot.skill.SkillManager;
import org.example.bot.rag.RAGPipeline;

/**
 * 服务上下文 — 消除 BotApp 中的长参数列表。
 * 所有服务集中管理，新增服务只需加一个字段，不影响现有方法签名。
 */
public class BotContext {
    public final AiService ai;
    public final SpeechService tts;
    public final CalculatorService calc;
    public final RandomService random;
    public final ExpressService express;
    public final FootballService football;
    public final DietService diet;
    public final WeatherServiceImpl weather;
    public final VisionService vision;
    public final ImageGenService imageGen;
    public final NewsService news;
    public final FinanceService finance;
    public final WebReaderService webReader;
    public final IdiomService idiom;
    public final GarbageService garbage;
    public final DatabaseService db;
    public final SchedulerService scheduler;
    public final ToolCenter toolCenter;
    public final SkillManager skillManager;
    public final RAGPipeline ragPipeline;

    public BotContext(AiService ai, SpeechService tts, CalculatorService calc,
                      RandomService random, ExpressService express, FootballService football,
                      DietService diet, WeatherServiceImpl weather, VisionService vision,
                      ImageGenService imageGen, NewsService news, FinanceService finance,
                      WebReaderService webReader,
                      IdiomService idiom, GarbageService garbage,
                      DatabaseService db, SchedulerService scheduler,
                      ToolCenter toolCenter, SkillManager skillManager, RAGPipeline ragPipeline) {
        this.ai = ai;
        this.tts = tts;
        this.calc = calc;
        this.random = random;
        this.express = express;
        this.football = football;
        this.diet = diet;
        this.weather = weather;
        this.vision = vision;
        this.imageGen = imageGen;
        this.news = news;
        this.finance = finance;
        this.webReader = webReader;
        this.idiom = idiom;
        this.garbage = garbage;
        this.db = db;
        this.scheduler = scheduler;
        this.toolCenter = toolCenter;
        this.skillManager = skillManager;
        this.ragPipeline = ragPipeline;
    }
}
