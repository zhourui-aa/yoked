
"""
天气查询控制台程序 (修复版)
功能：
1. 基础命令: help, version, status
2. 天气查询: 调用和风天气API (2026新版API)
3. 异常处理与日志记录
4. ilink SDK 集成（预留接口）

修复内容 (2026-07-16):
- API路径: /v2/city/lookup → /geo/v2/city/lookup
- 认证方式: Header → URL参数 key=
- 支持自定义API Host

作者: AI Assistant
日期: 2026-07-16
"""

import requests
import json
import logging
import sys
import time
from datetime import datetime
from enum import Enum

# ============================================================
# 配置区域 - 请替换为你的实际配置
# ============================================================

# 和风天气 API 配置
# 申请地址: https://console.qweather.com/
# 1. 注册账号
# 2. 创建项目 → 获取 API Key
# 3. 在控制台-设置中查看你的 API Host
# 4. 免费版: 1000次/天

QWEATHER_API_KEY = "d9315d971f0d47a69cd2406a4ab14534"  # <-- 替换为你的Key

# API Host (2026年起必须使用自己的API Host，原公共地址将逐步停止服务)
# 在控制台-设置中查看，格式如: abc1234xyz.def.qweatherapi.com
QWEATHER_API_HOST = "kx3jpj749m.re.qweatherapi.com"  # <-- 替换为你的API Host

# 程序版本信息
VERSION = "1.1.0"
BUILD_DATE = "2026-07-16"

# ============================================================
# 日志配置
# ============================================================

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S',
    handlers=[
        logging.StreamHandler(sys.stdout),
        logging.FileHandler('weather_app.log', encoding='utf-8')
    ]
)
logger = logging.getLogger(__name__)

# ============================================================
# 自定义异常类
# ============================================================

class WeatherAppError(Exception):
    """应用基础异常"""
    pass

class APIKeyNotSetError(WeatherAppError):
    """API Key 未设置"""
    pass

class CityNotFoundError(WeatherAppError):
    """城市未找到"""
    pass

class APIRequestError(WeatherAppError):
    """API 请求错误"""
    pass

class NetworkError(WeatherAppError):
    """网络错误"""
    pass

# ============================================================
# 天气服务类
# ============================================================

class WeatherService:
    """和风天气 API 服务封装 (2026新版)"""

    def __init__(self, api_key: str, api_host: str = "devapi.qweather.com"):
        if not api_key or api_key == "YOUR_QWEATHER_API_KEY_HERE":
            raise APIKeyNotSetError(
                "请先设置 QWEATHER_API_KEY！\n"
                "1. 访问 https://console.qweather.com/ 注册账号\n"
                "2. 创建项目 → 获取 API Key\n"
                "3. 在控制台-设置中查看你的 API Host"
            )
        self.api_key = api_key
        self.api_host = api_host

        # 构建基础URL
        self.base_url = f"https://{api_host}/v7"
        self.geo_url = f"https://{api_host}/geo/v2"

        self.session = requests.Session()
        # 2026新版: 认证通过URL参数 key= 传递，不再使用Header
        self.session.headers.update({
            'Content-Type': 'application/json',
            'Accept-Encoding': 'gzip',  # 支持Gzip压缩
        })
        logger.info(f"WeatherService 初始化完成 (API Host: {api_host})")

    def _make_request(self, url: str, params: dict = None, max_retries: int = 3) -> dict:
        """发送HTTP请求，带重试机制"""
        # 添加API Key到参数
        request_params = params.copy() if params else {}
        request_params['key'] = self.api_key

        for attempt in range(1, max_retries + 1):
            try:
                logger.info(f"HTTP请求: {url} (尝试 {attempt}/{max_retries})")
                logger.debug(f"请求参数: {request_params}")

                response = self.session.get(url, params=request_params, timeout=10)

                # 检查HTTP状态码
                if response.status_code == 404:
                    logger.error(f"404错误 - URL不存在: {response.url}")
                    raise APIRequestError(
                        f"API地址错误 (404): {url}\n"
                        f"请检查API Host是否正确，或联系和风天气客服"
                    )

                if response.status_code == 401:
                    logger.error("401错误 - API Key认证失败")
                    raise APIRequestError(
                        "API Key认证失败 (401)\n"
                        "请检查: 1. Key是否正确 2. Key类型是否匹配(Web API)"
                    )

                response.raise_for_status()
                data = response.json()

                # 检查和风天气API的业务错误码
                code = data.get('code')
                if code == '404':
                    error_msg = f"API业务错误: 查询的数据或地区不存在 (code={code})"
                    logger.error(error_msg)
                    raise CityNotFoundError(error_msg)
                elif code == '401':
                    error_msg = f"API业务错误: 认证失败 (code={code})"
                    logger.error(error_msg)
                    raise APIRequestError(error_msg)
                elif code == '402':
                    error_msg = f"API业务错误: 超过访问次数或余额不足 (code={code})"
                    logger.error(error_msg)
                    raise APIRequestError(error_msg)
                elif code == '403':
                    error_msg = f"API业务错误: 无访问权限 (code={code})"
                    logger.error(error_msg)
                    raise APIRequestError(error_msg)
                elif code == '429':
                    error_msg = f"API业务错误: 请求过于频繁 (code={code})"
                    logger.error(error_msg)
                    raise APIRequestError(error_msg)
                elif code not in ['200', '204']:
                    error_msg = f"API错误: code={code}"
                    logger.error(error_msg)
                    raise APIRequestError(error_msg)

                logger.info(f"请求成功: {url}")
                return data

            except requests.exceptions.ConnectionError as e:
                logger.error(f"网络连接错误 (尝试 {attempt}/{max_retries}): {e}")
                if attempt == max_retries:
                    raise NetworkError(f"网络连接失败，请检查网络: {e}")
                time.sleep(2 ** attempt)  # 指数退避

            except requests.exceptions.Timeout as e:
                logger.error(f"请求超时 (尝试 {attempt}/{max_retries}): {e}")
                if attempt == max_retries:
                    raise NetworkError(f"请求超时: {e}")
                time.sleep(2 ** attempt)

            except requests.exceptions.HTTPError as e:
                logger.error(f"HTTP错误: {e}")
                raise APIRequestError(f"HTTP请求失败: {e}")

            except (APIRequestError, CityNotFoundError):
                raise  # 直接抛出业务错误，不重试

            except Exception as e:
                logger.error(f"未知错误: {e}")
                raise WeatherAppError(f"请求异常: {e}")

    def search_city(self, city_name: str) -> str:
        """
        根据城市名称查询城市ID

        Args:
            city_name: 城市名称，如 "北京", "上海"

        Returns:
            城市ID (locationId)，如 "101010100"

        Raises:
            CityNotFoundError: 城市未找到
        """
        if not city_name or not city_name.strip():
            raise CityNotFoundError("城市名称不能为空！")

        # 2026新版: /geo/v2/city/lookup
        url = f"{self.geo_url}/city/lookup"
        params = {
            "location": city_name,
            "range": "cn",  # 限定中国
            "number": 1     # 只返回1个结果
        }

        logger.info(f"搜索城市: {city_name}")
        data = self._make_request(url, params)

        locations = data.get('location', [])
        if not locations:
            raise CityNotFoundError(f"未找到城市: {city_name}，请检查城市名称")

        city_id = locations[0]['id']
        city_full_name = locations[0]['name']
        logger.info(f"找到城市: {city_full_name} (ID: {city_id})")
        return city_id

    def get_current_weather(self, city_name: str) -> dict:
        """
        获取指定城市的实时天气

        Args:
            city_name: 城市名称

        Returns:
            格式化的天气信息字典
        """
        # 1. 查询城市ID
        city_id = self.search_city(city_name)

        # 2. 查询实时天气
        url = f"{self.base_url}/weather/now"
        params = {"location": city_id}

        logger.info(f"查询天气: {city_name} (ID: {city_id})")
        data = self._make_request(url, params)

        # 3. 解析并格式化数据
        now = data.get('now', {})
        weather_info = {
            'city': city_name,
            'temperature': f"{now.get('temp', 'N/A')}°C",
            'feels_like': f"{now.get('feelsLike', 'N/A')}°C",
            'condition': now.get('text', '未知'),
            'wind_direction': now.get('windDir', '未知'),
            'wind_scale': f"{now.get('windScale', 'N/A')}级",
            'wind_speed': f"{now.get('windSpeed', 'N/A')}km/h",
            'humidity': f"{now.get('humidity', 'N/A')}%",
            'pressure': f"{now.get('pressure', 'N/A')}hPa",
            'visibility': f"{now.get('vis', 'N/A')}km",
            'precipitation': f"{now.get('precip', '0')}mm",
            'update_time': data.get('updateTime', '未知')
        }

        logger.info(f"天气查询成功: {city_name} - {weather_info['condition']} {weather_info['temperature']}")
        return weather_info

    def get_forecast(self, city_name: str, days: int = 3) -> list:
        """
        获取天气预报

        Args:
            city_name: 城市名称
            days: 预报天数 (3/7/10/15/30)

        Returns:
            预报列表
        """
        city_id = self.search_city(city_name)

        url = f"{self.base_url}/weather/{days}d"
        params = {"location": city_id}

        logger.info(f"查询{days}天预报: {city_name}")
        data = self._make_request(url, params)

        daily = data.get('daily', [])
        forecast = []
        for day in daily:
            forecast.append({
                'date': day.get('fxDate', 'N/A'),
                'temp_high': f"{day.get('tempMax', 'N/A')}°C",
                'temp_low': f"{day.get('tempMin', 'N/A')}°C",
                'day_condition': day.get('textDay', '未知'),
                'night_condition': day.get('textNight', '未知'),
                'wind_day': f"{day.get('windDirDay', '未知')} {day.get('windScaleDay', 'N/A')}级"
            })

        logger.info(f"预报查询成功: {city_name}，共{len(forecast)}天")
        return forecast


# ============================================================
# 天气信息格式化输出
# ============================================================

def format_weather(weather_info: dict) -> str:
    """格式化天气信息为美观的字符串"""
    return f"""
┌─────────────────────────────────────────┐
│           🌤️  实时天气信息              │
├─────────────────────────────────────────┤
│  📍 城市:     {weather_info['city']:<25}│
│  🌡️ 温度:     {weather_info['temperature']:<25}│
│  🤒 体感:     {weather_info['feels_like']:<25}│
│  ☁️ 天气:     {weather_info['condition']:<25}│
│  💨 风向:     {weather_info['wind_direction']:<25}│
│  🌪️ 风力:     {weather_info['wind_scale']:<25}│
│  💧 湿度:     {weather_info['humidity']:<25}│
│  📊 气压:     {weather_info['pressure']:<25}│
│  👁️ 能见度:   {weather_info['visibility']:<25}│
│  🌧️ 降水量:   {weather_info['precipitation']:<25}│
├─────────────────────────────────────────┤
│  🕐 更新时间: {weather_info['update_time']:<24}│
└─────────────────────────────────────────┘
"""


def format_forecast(forecast: list, city_name: str) -> str:
    """格式化天气预报"""
    lines = [
        f"\n📅 {city_name} 未来{len(forecast)}天天气预报",
        "=" * 50
    ]
    for day in forecast:
        lines.append(f"""
📆 {day['date']}
   白天: {day['day_condition']} | 夜间: {day['night_condition']}
   温度: {day['temp_low']} ~ {day['temp_high']}
   风向: {day['wind_day']}
""")
    return "\n".join(lines)


# ============================================================
# 程序状态管理
# ============================================================

class AppStatus(Enum):
    """程序运行状态"""
    INITIALIZING = "初始化中"
    READY = "就绪"
    PROCESSING = "处理中"
    ERROR = "错误"
    SHUTDOWN = "已关闭"


class Application:
    """主应用程序类"""

    def __init__(self):
        self.status = AppStatus.INITIALIZING
        self.weather_service = None
        self.command_count = 0
        self.start_time = datetime.now()
        self.ilink_connected = False

        logger.info("=" * 50)
        logger.info("天气查询控制台程序启动")
        logger.info(f"版本: {VERSION}")
        logger.info("=" * 50)

        # 初始化天气服务
        try:
            self.weather_service = WeatherService(QWEATHER_API_KEY, QWEATHER_API_HOST)
            self.status = AppStatus.READY
            logger.info("天气服务初始化成功")
        except APIKeyNotSetError as e:
            logger.warning(f"天气服务未初始化: {e}")
            self.status = AppStatus.READY  # 仍可运行其他命令
        except Exception as e:
            logger.error(f"初始化失败: {e}")
            self.status = AppStatus.ERROR

    # ==================== 基础命令 ====================

    def cmd_help(self, args: list = None) -> str:
        """显示帮助信息"""
        self.command_count += 1
        return """
╔══════════════════════════════════════════════════════════════╗
║                    🌤️  天气查询控制台  帮助                  ║
╠══════════════════════════════════════════════════════════════╣
║  基础命令:                                                   ║
║    help              - 显示此帮助信息                        ║
║    version           - 显示程序版本信息                    ║
║    status            - 显示程序运行状态                    ║
║    quit / exit       - 退出程序                            ║
║                                                              ║
║  天气命令:                                                   ║
║    weather <城市>    - 查询城市实时天气                    ║
║    forecast <城市>   - 查询城市3天预报                     ║
║                                                              ║
║  示例:                                                       ║
║    weather 北京                                              ║
║    weather 上海                                              ║
║    forecast 广州                                             ║
║                                                              ║
║  ilink 命令 (预留):                                          ║
║    ilink connect     - 连接 ilink SDK                     ║
║    ilink send <消息>  - 发送文本消息                        ║
║    ilink receive     - 接收文本消息                        ║
║    ilink status      - 显示 ilink 连接状态                 ║
╚══════════════════════════════════════════════════════════════╝
"""

    def cmd_version(self, args: list = None) -> str:
        """显示版本信息"""
        self.command_count += 1
        return f"""
╔══════════════════════════════════════════════════════════════╗
║                      📦  版本信息                            ║
╠══════════════════════════════════════════════════════════════╣
║  程序名称:  天气查询控制台                                   ║
║  版本号:    {VERSION:<46}║
║  构建日期:  {BUILD_DATE:<46}║
║  Python:    {sys.version.split()[0]:<46}║
║  平台:      {sys.platform:<46}║
╚══════════════════════════════════════════════════════════════╝
"""

    def cmd_status(self, args: list = None) -> str:
        """显示程序状态"""
        self.command_count += 1
        uptime = datetime.now() - self.start_time

        weather_status = "✅ 已连接" if self.weather_service else "❌ 未连接 (API Key未设置)"
        ilink_status = "✅ 已连接" if self.ilink_connected else "❌ 未连接"

        return f"""
╔══════════════════════════════════════════════════════════════╗
║                      📊  程序状态                            ║
╠══════════════════════════════════════════════════════════════╣
║  运行状态:     {self.status.value:<42}║
║  运行时间:     {str(uptime).split('.')[0]:<42}║
║  命令计数:     {self.command_count:<42}║
║  天气服务:     {weather_status:<42}║
║  ilink SDK:    {ilink_status:<42}║
╚══════════════════════════════════════════════════════════════╝
"""

    # ==================== 天气命令 ====================

    def cmd_weather(self, args: list) -> str:
        """查询实时天气"""
        self.command_count += 1

        if not args:
            return "❌ 错误: 请提供城市名称！用法: weather <城市名>"

        if not self.weather_service:
            return "❌ 错误: 天气服务未初始化，请检查API Key设置"

        city_name = args[0]
        self.status = AppStatus.PROCESSING

        try:
            weather = self.weather_service.get_current_weather(city_name)
            self.status = AppStatus.READY
            return format_weather(weather)
        except CityNotFoundError as e:
            self.status = AppStatus.ERROR
            logger.error(f"城市未找到: {e}")
            return f"❌ {e}"
        except APIRequestError as e:
            self.status = AppStatus.ERROR
            logger.error(f"API请求错误: {e}")
            return f"❌ 天气API请求失败: {e}"
        except NetworkError as e:
            self.status = AppStatus.ERROR
            logger.error(f"网络错误: {e}")
            return f"❌ 网络连接问题: {e}"
        except Exception as e:
            self.status = AppStatus.ERROR
            logger.exception("未知错误")
            return f"❌ 发生未知错误: {e}"

    def cmd_forecast(self, args: list) -> str:
        """查询天气预报"""
        self.command_count += 1

        if not args:
            return "❌ 错误: 请提供城市名称！用法: forecast <城市名>"

        if not self.weather_service:
            return "❌ 错误: 天气服务未初始化"

        city_name = args[0]
        self.status = AppStatus.PROCESSING

        try:
            forecast = self.weather_service.get_forecast(city_name, days=3)
            self.status = AppStatus.READY
            return format_forecast(forecast, city_name)
        except Exception as e:
            self.status = AppStatus.ERROR
            logger.error(f"预报查询失败: {e}")
            return f"❌ 查询失败: {e}"

    # ==================== ilink SDK 命令 (预留接口) ====================

    def cmd_ilink_connect(self, args: list = None) -> str:
        """连接 ilink SDK"""
        self.command_count += 1
        logger.info("ilink connect 命令被调用")

        return """
🔗 ilink SDK 连接 (预留)
─────────────────────────
状态: 未实现
说明: 请在下午完成以下步骤:
  1. 安装 ilink SDK: pip install ilink-sdk (或根据文档安装)
  2. 导入并初始化客户端
  3. 配置连接参数 (服务器地址、端口、认证信息)
  4. 调用 connect() 建立连接
  5. 注册消息接收回调
"""

    def cmd_ilink_send(self, args: list) -> str:
        """通过 ilink 发送文本消息"""
        self.command_count += 1

        if not args:
            return "❌ 错误: 请提供消息内容！用法: ilink send <消息>"

        message = " ".join(args)
        logger.info(f"ilink send 命令被调用: {message}")

        return f"""
📤 ilink 发送消息 (预留)
─────────────────────────
消息内容: {message}
状态: 未实现 (ilink 未连接)
说明: 请先执行 'ilink connect' 建立连接
"""

    def cmd_ilink_receive(self, args: list = None) -> str:
        """接收 ilink 文本消息"""
        self.command_count += 1
        logger.info("ilink receive 命令被调用")

        return """
📥 ilink 接收消息 (预留)
─────────────────────────
状态: 未实现
说明: 请在 ilink SDK 中实现消息接收回调:
  def _on_ilink_message(self, message):
      logger.info(f"收到消息: {message}")
      # 处理收到的文本消息
"""

    def cmd_ilink_status(self, args: list = None) -> str:
        """显示 ilink 连接状态"""
        self.command_count += 1
        status = "✅ 已连接" if self.ilink_connected else "❌ 未连接"
        return f"🔗 ilink SDK 状态: {status}"

    # ==================== 命令路由 (Switch Case 逻辑) ====================

    def execute(self, command_line: str) -> str:
        """执行命令 - 使用字典映射实现 Switch Case"""
        parts = command_line.strip().split()
        if not parts:
            return ""

        cmd = parts[0].lower()
        args = parts[1:]

        # 命令映射表 (Switch Case 逻辑)
        commands = {
            'help': self.cmd_help,
            'version': self.cmd_version,
            'status': self.cmd_status,
            'weather': self.cmd_weather,
            'forecast': self.cmd_forecast,
            'ilink': self._handle_ilink,  # 子命令路由
            'quit': lambda x: self._shutdown(),
            'exit': lambda x: self._shutdown(),
        }

        handler = commands.get(cmd)
        if handler:
            try:
                return handler(args)
            except Exception as e:
                logger.exception(f"命令执行错误: {cmd}")
                return f"❌ 命令执行出错: {e}"
        else:
            return f"❌ 未知命令: '{cmd}'，输入 'help' 查看可用命令"

    def _handle_ilink(self, args: list) -> str:
        """ilink 子命令路由 - 嵌套 Switch Case"""
        if not args:
            return "❌ 错误: ilink 需要子命令。用法: ilink <connect|send|receive|status>"

        sub_cmd = args[0].lower()
        sub_args = args[1:]

        ilink_commands = {
            'connect': self.cmd_ilink_connect,
            'send': self.cmd_ilink_send,
            'receive': self.cmd_ilink_receive,
            'status': self.cmd_ilink_status,
        }

        handler = ilink_commands.get(sub_cmd)
        if handler:
            return handler(sub_args)
        else:
            return f"❌ 未知 ilink 子命令: '{sub_cmd}'"

    def _shutdown(self) -> str:
        """关闭程序"""
        self.status = AppStatus.SHUTDOWN
        logger.info("程序关闭")
        return "👋 再见！"

    def run_interactive(self):
        """交互式运行"""
        print(self.cmd_help())

        while self.status != AppStatus.SHUTDOWN:
            try:
                command_line = input("\n🌤️  weather> ").strip()
                if not command_line:
                    continue

                result = self.execute(command_line)
                if result:
                    print(result)

            except KeyboardInterrupt:
                print("\n👋 程序被中断")
                break
            except EOFError:
                break
            except Exception as e:
                logger.exception("交互循环错误")
                print(f"❌ 发生错误: {e}")


# ============================================================
# 测试函数
# ============================================================

def run_tests():
    """运行测试"""
    print("\n" + "=" * 60)
    print("🧪  开始运行测试")
    print("=" * 60)

    app = Application()

    # 测试1: 基础命令
    print("\n📋 测试1: 基础命令")
    print("-" * 40)
    print(app.execute("help"))
    print(app.execute("version"))
    print(app.execute("status"))

    # 测试2: 异常处理 - 空城市名
    print("\n📋 测试2: 异常处理 - 空城市名")
    print("-" * 40)
    print(app.execute("weather"))

    # 测试3: 异常处理 - 未知命令
    print("\n📋 测试3: 异常处理 - 未知命令")
    print("-" * 40)
    print(app.execute("unknown_command"))

    # 测试4: ilink 预留命令
    print("\n📋 测试4: ilink 预留命令")
    print("-" * 40)
    print(app.execute("ilink status"))
    print(app.execute("ilink connect"))
    print(app.execute("ilink send 你好，这是测试消息"))
    print(app.execute("ilink receive"))

    # 测试5: 天气查询 (需要有效的API Key)
    print("\n📋 测试5: 天气查询")
    print("-" * 40)
    if QWEATHER_API_KEY != "YOUR_QWEATHER_API_KEY_HERE":
        test_cities = ["北京", "上海", "广州", "深圳"]
        for city in test_cities:
            print(f"\n🔍 查询: {city}")
            print(app.execute(f"weather {city}"))

        # 测试预报
        print("\n📋 测试6: 天气预报")
        print("-" * 40)
        print(app.execute("forecast 北京"))

        # 测试错误城市
        print("\n📋 测试7: 错误城市名处理")
        print("-" * 40)
        print(app.execute("weather 不存在的城市12345"))
    else:
        print("⚠️  API Key 未设置，跳过天气查询测试")
        print("   请访问 https://console.qweather.com/ 申请免费API Key")

    print("\n" + "=" * 60)
    print("✅ 测试完成")
    print("=" * 60)


# ============================================================
# 主入口
# ============================================================

if __name__ == "__main__":
    import sys

    if len(sys.argv) > 1 and sys.argv[1] == "--test":
        run_tests()
    else:
        app = Application()
        app.run_interactive()