package com.weather;

import com.weather.exception.WeatherException;
import com.weather.model.WeatherResponse;
import com.weather.service.WeatherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Scanner;

/**
 * 天气查询系统主程序
 *
 * 用法：
 *   直接运行 main 方法，在控制台输入城市名即可查询
 *   输入 "exit" 退出程序
 */
public class WeatherApplication {

    private static final Logger log = LoggerFactory.getLogger(WeatherApplication.class);

    public static void main(String[] args) {
        log.info("========== 天气查询系统启动 ==========");//打印日志

        // ====== 初始化服务 ======
        WeatherService service;
        try {
            service = new WeatherService();
        } catch (Exception e) {
            log.error("服务初始化失败", e);
            System.err.println("❌ 初始化失败: " + e.getMessage());
            return;
        }

        System.out.println();
        System.out.println("╔══════════════════════════════╗");
        System.out.println("║      天 气 查 询 系 统       ║");
        System.out.println("╠══════════════════════════════╣");
        System.out.println("║  输入城市名 → 查询实时天气  ║");
        System.out.println("║  输入 exit  → 退出程序      ║");
        System.out.println("╚══════════════════════════════╝");

        // ====== 交互循环 ======
        try (Scanner scanner = new Scanner(System.in)) //读取控制台键盘输入
        {
            while (true) {
                System.out.print("\n🌍 请输入城市名 > ");
                String input = scanner.nextLine().trim();//读取一整行，trim去首尾空格，决定了输入被读取的顺序（控制台显示位置）

                // 退出判断
                if ("退出".equalsIgnoreCase(input)) {
                    System.out.println("👋 再见！");
                    log.info("用户退出程序");
                    break;
                }

                // 查询天气
                try {
                    WeatherResponse resp = service.queryByCity(input);
                    printWeather(resp);//调用输出
                } catch (WeatherException e) {
                    // 业务异常：城市名空、找不到城市、API失败等
                    System.err.print("⚠ " + e.getMessage() +"，请重新输入!");//因为报错的红色渲染始终让err在下一轮循环开始后才打印到控制台，导致输出错位；这个可以让当前执行代码的线程进入阻塞，暂停指定时长。
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ex) //线程睡眠时如果被外部打断会抛出，规范写法需要捕获；
                    {
                        Thread.currentThread().interrupt();//恢复线程中断状态，是 Java 线程标准规范写法。
                    }
                } catch (Exception e) {
                    // 未预期的系统异常
                    log.error("未预期的异常", e);
                    System.err.println("❌ 系统异常，请稍后重试。详情见日志。");
                }
            }
        }

        log.info("========== 天气查询系统结束 ==========");
    }

    /**
     * 格式化打印天气信息
     */
    private static void printWeather(WeatherResponse resp) {
        System.out.println();
        System.out.println("┌──────────────────────────────────┐");
        System.out.printf ("│  📍 %-10s                  │%n", resp.getCityName());
        System.out.println("├──────────────────────────────────┤");
        System.out.printf ("│  温度    : %4s °C              │%n", resp.getNow().getTemp());
        System.out.printf ("│  体感    : %4s °C              │%n", resp.getNow().getFeelsLike());
        System.out.printf ("│  天气    : %s                   │%n", resp.getNow().getText());
        System.out.printf ("│  风向    : %s                   │%n", resp.getNow().getWindDir());
        System.out.printf ("│  风力    : %s 级                │%n", resp.getNow().getWindScale());
        System.out.printf ("│  湿度    : %s%%                  │%n", resp.getNow().getHumidity());
        System.out.printf ("│  气压    : %s hPa               │%n", resp.getNow().getPressure());
        System.out.println("└──────────────────────────────────┘");
    }
}