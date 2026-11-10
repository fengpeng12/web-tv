package com.llq.hello

import android.content.Context

/**
 * 频道存储：按自定义分类组织。
 * 持久化格式每行一条：分类 \t 频道名 \t 网址
 */
class ChannelStore(context: Context) {

    private val sp = context.getSharedPreferences("channels", Context.MODE_PRIVATE)

    data class Channel(val group: String, val name: String, val url: String)

    fun load(): MutableList<Channel> {
        val raw = sp.getString(KEY, "").orEmpty()
        val list = mutableListOf<Channel>()
        if (raw.isEmpty()) return list
        for (line in raw.split("\n")) {
            if (line.isEmpty()) continue
            val parts = line.split('\t')
            if (parts.size < 3) continue
            val group = parts[0]
            val name = parts[1]
            val url = parts[2]
            if (url.isNotEmpty()) list.add(Channel(group, name, url))
        }
        return list
    }

    private fun save(list: List<Channel>) {
        val sb = StringBuilder()
        for (c in list) {
            sb.append(clean(c.group)).append('\t')
                .append(clean(c.name)).append('\t')
                .append(c.url.replace("\n", "").replace("\t", ""))
                .append('\n')
        }
        sp.edit().putString(KEY, sb.toString()).apply()
    }

    private fun clean(s: String): String =
        s.replace("\t", " ").replace("\n", " ")

    /** 是否是内置频道：分类+名称命中内置表即视为内置（内置频道不可编辑、不可删除）。 */
    fun isBuiltin(group: String, name: String): Boolean =
        BUILTINS.any { it.group == group && it.name == name }

    /** 添加或更新频道；同分类同名视为更新。 */
    fun add(group: String, name: String, url: String) {
        val list = load()
        val idx = list.indexOfFirst { it.group == group && it.name == name }
        if (idx >= 0) list[idx] = Channel(group, name, url) else list.add(Channel(group, name, url))
        save(list)
    }

    fun remove(group: String, name: String) {
        // 内置频道受保护，不参与删除
        if (isBuiltin(group, name)) return
        val list = load()
        list.removeAll { it.group == group && it.name == name }
        save(list)
    }

    /** 修改频道：按原分类+原名定位，可同时改分类、名称、网址。 */
    fun update(oldGroup: String, oldName: String, group: String, name: String, url: String) {
        // 内置频道受保护，不参与编辑
        if (isBuiltin(oldGroup, oldName)) return
        val list = load()
        val idx = list.indexOfFirst { it.group == oldGroup && it.name == oldName }
        if (idx < 0) {
            // 原条目已不存在（比如别处删掉了），直接按新值补回
            list.add(Channel(group, name, url))
        } else {
            list[idx] = Channel(group, name, url)
            // 改了名或分类后可能与既有条目重名：去重保留新值
            val dup = list.indexOfFirst { it !== list[idx] && it.group == group && it.name == name }
            if (dup >= 0) list.removeAt(dup)
        }
        save(list)
    }

    /** 删除某个分组下的全部频道（内置频道保留）。 */
    fun purgeGroup(group: String) {
        val list = load()
        val before = list.size
        list.removeAll { it.group == group && !isBuiltin(it.group, it.name) }
        if (list.size != before) save(list)
    }

    /** 现有分类，按首次出现顺序去重。 */
    fun groups(): List<String> {
        val seen = linkedSetOf<String>()
        for (c in load()) seen.add(c.group)
        return seen.toList()
    }

    /**
     * 内置频道受保护：每次启动都按内置表校正一遍。
     * 被删掉的补回、被改过的还原到正确网址，用户自定义频道不受影响。
     */
    fun seedBuiltins() {
        // 一次性清理：OXAX 分组清空（分类本身保留，见 grouped()），不再播种
        if (!sp.getBoolean(KEY_PURGED_OXAX, false)) {
            val cur = load()
            val kept = cur.filterNot { it.group == "OXAX" }
            if (kept.size != cur.size) save(kept)
            sp.edit().putBoolean(KEY_PURGED_OXAX, true).apply()
        }
        // 一次性清理：曾把 OXAX/Playboy TV 播种为内置后又撤销，删掉遗留条目
        if (!sp.getBoolean(KEY_PURGED_PLAYBOY, false)) {
            val cur = load()
            val kept = cur.filterNot { it.group == "OXAX" && it.name == "Playboy TV" }
            if (kept.size != cur.size) save(kept)
            sp.edit().putBoolean(KEY_PURGED_PLAYBOY, true).apply()
        }
        // 一次性清理：删除「卫视」分类及其全部频道
        if (!sp.getBoolean(KEY_PURGED_WEISHI, false)) {
            val cur = load()
            val kept = cur.filterNot { it.group == "卫视" }
            if (kept.size != cur.size) save(kept)
            sp.edit().putBoolean(KEY_PURGED_WEISHI, true).apply()
        }
        val original = load()
        // 先摘掉所有与内置表同名的旧条目，稍后按内置表顺序重新插入
        val list = original.filterNot { isBuiltin(it.group, it.name) || it.group == "福建" }.toMutableList()
        val inserted = HashMap<String, Int>()
        for (items in BUILTIN_GROUPS.map { it.third }) {
            for (b in items) {
                val start = list.indexOfFirst { it.group == b.group }
                if (start < 0) {
                    list.add(b)
                } else {
                    // 插在分组开头，内置频道排在自定义频道前面
                    list.add(start + (inserted[b.group] ?: 0), b)
                }
                inserted[b.group] = (inserted[b.group] ?: 0) + 1
            }
        }
        if (list != original) save(list)
    }

    /** 同分类同名只保留第一条，避免内置表变动后残留重复条目。 */
    fun dedupe() {
        val list = load()
        val seen = HashSet<String>()
        val kept = list.filter { seen.add(it.group + "\u0001" + it.name) }
        if (kept.size != list.size) save(kept)
    }

    /** 按分类分组，保持插入顺序；空分类（如已清空的 OXAX）也保留标题。 */
    fun grouped(): LinkedHashMap<String, MutableList<Channel>> {
        val map = LinkedHashMap<String, MutableList<Channel>>()
        for (g in EMPTY_GROUPS) map.getOrPut(g) { mutableListOf() }
        for (c in load()) {
            map.getOrPut(c.group) { mutableListOf() }.add(c)
        }
        return map
    }

    private companion object {
        const val KEY = "items"
        const val KEY_PURGED_PLAYBOY = "purged_oxax_playboy_v1"
        const val KEY_PURGED_OXAX = "purged_oxax_channels_v1"

        /** 允许「空着但保留标题」的分类：频道删空后仍出现在面板上。 */
        private val EMPTY_GROUPS = listOf("OXAX")
        const val KEY_PURGED_WEISHI = "purged_weishi_v1"
        const val KEY_SEEDED = "seeded_builtins_v1"

        /** 央视频 33 个卫视频道（tv/home 左栏 tabB，各频道独立 pid 直达）。 */
        private val WEISHI_YSP = listOf(
            Channel("央视频", "北京卫视", "https://www.yangshipin.cn/tv/home?pid=600002309"),
            Channel("央视频", "江苏卫视", "https://www.yangshipin.cn/tv/home?pid=600002521"),
            Channel("央视频", "东方卫视", "https://www.yangshipin.cn/tv/home?pid=600002483"),
            Channel("央视频", "浙江卫视", "https://www.yangshipin.cn/tv/home?pid=600002520"),
            Channel("央视频", "湖南卫视", "https://www.yangshipin.cn/tv/home?pid=600002475"),
            Channel("央视频", "湖北卫视", "https://www.yangshipin.cn/tv/home?pid=600002508"),
            Channel("央视频", "广东卫视", "https://www.yangshipin.cn/tv/home?pid=600002485"),
            Channel("央视频", "广西卫视", "https://www.yangshipin.cn/tv/home?pid=600002509"),
            Channel("央视频", "黑龙江卫视", "https://www.yangshipin.cn/tv/home?pid=600002498"),
            Channel("央视频", "海南卫视", "https://www.yangshipin.cn/tv/home?pid=600002506"),
            Channel("央视频", "重庆卫视", "https://www.yangshipin.cn/tv/home?pid=600002531"),
            Channel("央视频", "深圳卫视", "https://www.yangshipin.cn/tv/home?pid=600002481"),
            Channel("央视频", "四川卫视", "https://www.yangshipin.cn/tv/home?pid=600002516"),
            Channel("央视频", "河南卫视", "https://www.yangshipin.cn/tv/home?pid=600002525"),
            Channel("央视频", "福建东南卫视", "https://www.yangshipin.cn/tv/home?pid=600002484"),
            Channel("央视频", "贵州卫视", "https://www.yangshipin.cn/tv/home?pid=600002490"),
            Channel("央视频", "江西卫视", "https://www.yangshipin.cn/tv/home?pid=600002503"),
            Channel("央视频", "辽宁卫视", "https://www.yangshipin.cn/tv/home?pid=600002505"),
            Channel("央视频", "安徽卫视", "https://www.yangshipin.cn/tv/home?pid=600002532"),
            Channel("央视频", "河北卫视", "https://www.yangshipin.cn/tv/home?pid=600002493"),
            Channel("央视频", "山东卫视", "https://www.yangshipin.cn/tv/home?pid=600002513"),
            Channel("央视频", "天津卫视", "https://www.yangshipin.cn/tv/home?pid=600152137"),
            Channel("央视频", "吉林卫视", "https://www.yangshipin.cn/tv/home?pid=600190405"),
            Channel("央视频", "陕西卫视", "https://www.yangshipin.cn/tv/home?pid=600190400"),
            Channel("央视频", "甘肃卫视", "https://www.yangshipin.cn/tv/home?pid=600190408"),
            Channel("央视频", "宁夏卫视", "https://www.yangshipin.cn/tv/home?pid=600190737"),
            Channel("央视频", "内蒙古卫视", "https://www.yangshipin.cn/tv/home?pid=600190401"),
            Channel("央视频", "云南卫视", "https://www.yangshipin.cn/tv/home?pid=600190402"),
            Channel("央视频", "山西卫视", "https://www.yangshipin.cn/tv/home?pid=600190407"),
            Channel("央视频", "青海卫视", "https://www.yangshipin.cn/tv/home?pid=600190406"),
            Channel("央视频", "西藏卫视", "https://www.yangshipin.cn/tv/home?pid=600190403"),
            Channel("央视频", "中国教育电视台1频道", "https://www.yangshipin.cn/tv/home?pid=600171827"),
            Channel("央视频", "新疆卫视", "https://www.yangshipin.cn/tv/home?pid=600152138")
        )

        /** 齐鲁网「直播山东」的 9 个电视频道。 */
        private val SHANDONG = listOf(
            Channel("山东", "山东卫视", "https://v.iqilu.com/live/sdtv/"),
            Channel("山东", "齐鲁频道", "https://v.iqilu.com/live/qlpd/"),
            Channel("山东", "新闻频道", "https://v.iqilu.com/live/ggpd/"),
            Channel("山东", "农科频道", "https://v.iqilu.com/live/nkpd/"),
            Channel("山东", "少儿频道", "https://v.iqilu.com/live/sepd/"),
            Channel("山东", "文旅频道", "https://v.iqilu.com/live/yspd/"),
            Channel("山东", "生活频道", "https://v.iqilu.com/live/shpd/"),
            Channel("山东", "综艺频道", "https://v.iqilu.com/live/zypd/"),
            Channel("山东", "体育频道", "https://v.iqilu.com/live/typd/")
        )

        /**
         * 江苏台 9 个频道（顺序与站点 jstvList 数组一致）。
         * 站点频道卡是精灵图、卡片上没有文字，按名字点不到；
         * 每张卡片的 background-position 唯一，所以链接里带上
         * ch=（名称，供人看）和 bg=（精灵图横坐标，供脚本定位）。
         * 注意：精灵图中间有空位，坐标不连续（如 -196px 是空的），
         * 必须照抄站点源码里的 position，不能按 28px 步长推算。
         */
        private val JIANGSU = listOf(
            // 4K 超高清是站点接口动态下发的频道，不在静态表里、没有精灵图坐标，
            // 因此不带 bg= 参数，由脚本按频道名文字定位卡片
            Channel("江苏", "江苏卫视4K超高清", "https://live.jstv.com/?ch=%E6%B1%9F%E8%8B%8F%E5%8D%AB%E8%A7%864K"),
            Channel("江苏", "城市频道", "https://live.jstv.com/?ch=%E6%B1%9F%E8%8B%8F%E5%9F%8E%E5%B8%82&bg=-28px"),
            Channel("江苏", "公共新闻", "https://live.jstv.com/?ch=%E6%B1%9F%E8%8B%8F%E6%96%B0%E9%97%BB&bg=-56px"),
            Channel("江苏", "综艺", "https://live.jstv.com/?ch=%E6%B1%9F%E8%8B%8F%E7%BB%BC%E8%89%BA&bg=-84px"),
            Channel("江苏", "影视", "https://live.jstv.com/?ch=%E6%B1%9F%E8%8B%8F%E5%BD%B1%E8%A7%86&bg=-112px"),
            Channel("江苏", "体育休闲", "https://live.jstv.com/?ch=%E4%BD%93%E8%82%B2%E4%BC%91%E9%97%B2&bg=-140px"),
            Channel("江苏", "教育", "https://live.jstv.com/?ch=%E6%B1%9F%E8%8B%8F%E6%95%99%E8%82%B2&bg=-168px"),
            Channel("江苏", "江苏国际", "https://live.jstv.com/?ch=%E6%B1%9F%E8%8B%8F%E5%9B%BD%E9%99%85&bg=-252px"),
            Channel("江苏", "优漫卡通", "https://live.jstv.com/?ch=%E4%BC%98%E6%BC%AB%E5%8D%A1%E9%80%9A&bg=-308px")
        )

        /** 央视网直播的 20 个 CCTV 频道（主频道走央视频 pid 页面；CCTV-4 欧/美央视频未收录，保留央视网）。 */
        private val CCTV = listOf(
            Channel("CCTV", "CCTV-1 综合", "https://www.yangshipin.cn/tv/home?pid=600001859"),
            Channel("CCTV", "CCTV-2 财经", "https://www.yangshipin.cn/tv/home?pid=600001800"),
            Channel("CCTV", "CCTV-3 综艺", "https://www.yangshipin.cn/tv/home?pid=600001801"),
            Channel("CCTV", "CCTV-4 中文国际（亚）", "https://www.yangshipin.cn/tv/home?pid=600001814"),
            Channel("CCTV", "CCTV-5 体育", "https://www.yangshipin.cn/tv/home?pid=600001818"),
            Channel("CCTV", "CCTV-5+ 体育赛事", "https://www.yangshipin.cn/tv/home?pid=600001817"),
            Channel("CCTV", "CCTV-6 电影", "https://www.yangshipin.cn/tv/home?pid=600108442"),
            Channel("CCTV", "CCTV-7 国防军事", "https://www.yangshipin.cn/tv/home?pid=600004092"),
            Channel("CCTV", "CCTV-8 电视剧", "https://www.yangshipin.cn/tv/home?pid=600001803"),
            Channel("CCTV", "CCTV-9 纪录", "https://www.yangshipin.cn/tv/home?pid=600004078"),
            Channel("CCTV", "CCTV-10 科教", "https://www.yangshipin.cn/tv/home?pid=600001805"),
            Channel("CCTV", "CCTV-11 戏曲", "https://www.yangshipin.cn/tv/home?pid=600001806"),
            Channel("CCTV", "CCTV-12 社会与法", "https://www.yangshipin.cn/tv/home?pid=600001807"),
            Channel("CCTV", "CCTV-13 新闻", "https://www.yangshipin.cn/tv/home?pid=600001811"),
            Channel("CCTV", "CCTV-14 少儿", "https://www.yangshipin.cn/tv/home?pid=600001809"),
            Channel("CCTV", "CCTV-15 音乐", "https://www.yangshipin.cn/tv/home?pid=600001815"),
            Channel("CCTV", "CCTV-16 奥林匹克", "https://www.yangshipin.cn/tv/home?pid=600098637"),
            Channel("CCTV", "CCTV-17 农业农村", "https://www.yangshipin.cn/tv/home?pid=600001810"),
            Channel("CCTV", "CCTV-4 中文国际（欧）", "https://tv.cctv.com/live/cctveurope/index.shtml"),
            Channel("CCTV", "CCTV-4 中文国际（美）", "https://tv.cctv.com/live/cctvamerica/index.shtml")
        )

        /** 央视频补充的央视 20 个频道（CGTN 各语种 + 剧场/付费专业频道 + 8K）。 */
        private val CCTV_YSP = listOf(
            Channel("CCTV", "CGTN 英语", "https://www.yangshipin.cn/tv/home?pid=600014550"),
            Channel("CCTV", "CGTN 法语", "https://www.yangshipin.cn/tv/home?pid=600084704"),
            Channel("CCTV", "CGTN 俄语", "https://www.yangshipin.cn/tv/home?pid=600084758"),
            Channel("CCTV", "CGTN 阿拉伯语", "https://www.yangshipin.cn/tv/home?pid=600084782"),
            Channel("CCTV", "CGTN 西班牙语", "https://www.yangshipin.cn/tv/home?pid=600084744"),
            Channel("CCTV", "CGTN 纪录", "https://www.yangshipin.cn/tv/home?pid=600084781"),
            Channel("CCTV", "CCTV 风云剧场", "https://www.yangshipin.cn/tv/home?pid=600099658"),
            Channel("CCTV", "CCTV 第一剧场", "https://www.yangshipin.cn/tv/home?pid=600099655"),
            Channel("CCTV", "CCTV 怀旧剧场", "https://www.yangshipin.cn/tv/home?pid=600099620"),
            Channel("CCTV", "CCTV 世界地理", "https://www.yangshipin.cn/tv/home?pid=600099637"),
            Channel("CCTV", "CCTV 风云音乐", "https://www.yangshipin.cn/tv/home?pid=600099660"),
            Channel("CCTV", "CCTV 兵器科技", "https://www.yangshipin.cn/tv/home?pid=600099649"),
            Channel("CCTV", "CCTV 风云足球", "https://www.yangshipin.cn/tv/home?pid=600099636"),
            Channel("CCTV", "CCTV 高尔夫·网球", "https://www.yangshipin.cn/tv/home?pid=600099659"),
            Channel("CCTV", "CCTV 女性时尚", "https://www.yangshipin.cn/tv/home?pid=600099650"),
            Channel("CCTV", "CCTV 文化精品", "https://www.yangshipin.cn/tv/home?pid=600099653"),
            Channel("CCTV", "CCTV 台球", "https://www.yangshipin.cn/tv/home?pid=600099652"),
            Channel("CCTV", "CCTV 电视指南", "https://www.yangshipin.cn/tv/home?pid=600099656"),
            Channel("CCTV", "CCTV 卫生健康", "https://www.yangshipin.cn/tv/home?pid=600099651"),
            Channel("CCTV", "CCTV-8K", "https://www.yangshipin.cn/tv/home?pid=600156816")
        )

        /** B站直播：用户指定直播间 + 长期在播的热门直播间。 */
        private val BILIBILI = listOf(
            Channel("B站", "光影如梦", "https://live.bilibili.com/22450590"),
            Channel("B站", "老实憨厚的笑笑", "https://live.bilibili.com/545068"),
            Channel("B站", "Asaki大人", "https://live.bilibili.com/6154037"),
            Channel("B站", "hanser", "https://live.bilibili.com/48743"),
            Channel("B站", "炫神", "https://live.bilibili.com/14709735"),
            Channel("B站", "赵俊日", "https://live.bilibili.com/22174141"),
            Channel("B站", "辉耀计划", "https://live.bilibili.com/24158116")
        )

        /**
         * 浙江广电（新蓝网）10 个电视频道。
         * 用站点自有直播页：URL 末段就是频道号，一个频道一个页面，无需模拟换台。
         * 直链 CDN 有防盗链签名（auth_key 由站点 JS 现算），直接抓 m3u8 交给原生播放器
         * 会因签名方式不符而崩溃，所以这里与其它卫视一致，走 WebView 播放页面。
         */
        private val ZHEJIANG = listOf(
            Channel("浙江", "浙江卫视", "https://www.cztv.com/liveTV/101"),
            Channel("浙江", "钱江都市", "https://www.cztv.com/liveTV/102"),
            Channel("浙江", "经视生活", "https://www.cztv.com/liveTV/103"),
            Channel("浙江", "教科影视", "https://www.cztv.com/liveTV/104"),
            Channel("浙江", "民生休闲", "https://www.cztv.com/liveTV/106"),
            Channel("浙江", "公共新闻", "https://www.cztv.com/liveTV/107"),
            Channel("浙江", "浙江少儿", "https://www.cztv.com/liveTV/108"),
            Channel("浙江", "浙江国际", "https://www.cztv.com/liveTV/110"),
            Channel("浙江", "好易购", "https://www.cztv.com/liveTV/111"),
            Channel("浙江", "之江记录", "https://www.cztv.com/liveTV/112")
        )

        /**
         * 广东广播电视台（荔枝网 www.gdtv.cn）19 个电视频道。
         * 每频道一个独立页面 /tvChannelDetail/<id>，页面用阿里云 Prism 播放器，
         * 与已有 GDTV_JS 注入脚本对应，无需模拟点击换台。
         */
        private val GUANGDONG = listOf(
            Channel("广东", "广东卫视", "https://www.gdtv.cn/tvChannelDetail/43"),
            Channel("广东", "广东珠江", "https://www.gdtv.cn/tvChannelDetail/44"),
            Channel("广东", "广东新闻", "https://www.gdtv.cn/tvChannelDetail/45"),
            Channel("广东", "广东民生", "https://www.gdtv.cn/tvChannelDetail/48"),
            Channel("广东", "广东体育", "https://www.gdtv.cn/tvChannelDetail/47"),
            Channel("广东", "大湾区卫视", "https://www.gdtv.cn/tvChannelDetail/51"),
            Channel("广东", "大湾区卫视（海外版）", "https://www.gdtv.cn/tvChannelDetail/46"),
            Channel("广东", "广东影视", "https://www.gdtv.cn/tvChannelDetail/53"),
            Channel("广东", "4K超高清", "https://www.gdtv.cn/tvChannelDetail/16"),
            Channel("广东", "广东少儿", "https://www.gdtv.cn/tvChannelDetail/54"),
            Channel("广东", "嘉佳卡通", "https://www.gdtv.cn/tvChannelDetail/66"),
            Channel("广东", "南方购物", "https://www.gdtv.cn/tvChannelDetail/42"),
            Channel("广东", "岭南戏曲", "https://www.gdtv.cn/tvChannelDetail/15"),
            Channel("广东", "广东移动", "https://www.gdtv.cn/tvChannelDetail/74"),
            Channel("广东", "广东台经典剧", "https://www.gdtv.cn/tvChannelDetail/100"),
            Channel("广东", "纪录片", "https://www.gdtv.cn/tvChannelDetail/94"),
            Channel("广东", "健康", "https://www.gdtv.cn/tvChannelDetail/99"),
            Channel("广东", "GRTN生活", "https://www.gdtv.cn/tvChannelDetail/102")
        )

        /**
         * 江西网络广播电视台（www.jxntv.cn/live）8 个电视频道。
         * 全站共用一个直播页，点左侧频道条换台、地址不变，只能在脚本里模拟点击；
         * 因此链接统一指向该页，用 ?ch=频道名 告诉脚本要切到哪个频道。
         * 注意参数必须放在 # 之前，否则 location.search 取不到。
         */
        private val JXNTV = listOf(
            Channel("江西", "江西卫视", "https://www.jxntv.cn/live/?ch=%E6%B1%9F%E8%A5%BF%E5%8D%AB%E8%A7%86#/"),
            Channel("江西", "都市频道", "https://www.jxntv.cn/live/?ch=%E9%83%BD%E5%B8%82%E9%A2%91%E9%81%93#/"),
            Channel("江西", "经济生活频道", "https://www.jxntv.cn/live/?ch=%E7%BB%8F%E6%B5%8E%E7%94%9F%E6%B4%BB%E9%A2%91%E9%81%93#/"),
            Channel("江西", "公共·农业频道", "https://www.jxntv.cn/live/?ch=%E5%85%AC%E5%85%B1%C2%B7%E5%86%9C%E4%B8%9A%E9%A2%91%E9%81%93#/"),
            Channel("江西", "少儿频道", "https://www.jxntv.cn/live/?ch=%E5%B0%91%E5%84%BF%E9%A2%91%E9%81%93#/"),
            Channel("江西", "新闻频道", "https://www.jxntv.cn/live/?ch=%E6%96%B0%E9%97%BB%E9%A2%91%E9%81%93#/"),
            Channel("江西", "移动电视频道", "https://www.jxntv.cn/live/?ch=%E7%A7%BB%E5%8A%A8%E7%94%B5%E8%A7%86%E9%A2%91%E9%81%93#/"),
            Channel("江西", "陶瓷频道", "https://www.jxntv.cn/live/?ch=%E9%99%B6%E7%93%B7%E9%A2%91%E9%81%93#/")
        )

        /**
         * 广西网络广播电视台（tv.gxtv.cn）。
         * 每个频道一个独立页面 channelivePlay_<id>.html，进入即自动播放，
         * 播放器是站点自研的 dhc（video#dhc-video，MSE/blob 流）。
         */
        private val GXTV = listOf(
            Channel("广西", "广西卫视", "https://tv.gxtv.cn/channel/channelivePlay_e7a7ab7df9fe11e88bcfe41f13b60c62.html"),
            Channel("广西", "综艺旅游频道", "https://tv.gxtv.cn/channel/channelivePlay_f3335975f9fe11e88bcfe41f13b60c62.html"),
            Channel("广西", "都市频道", "https://tv.gxtv.cn/channel/channelivePlay_fdbaf085f9fe11e88bcfe41f13b60c62.html"),
            Channel("广西", "影视频道", "https://tv.gxtv.cn/channel/channelivePlay_5e923d82058e11e9ba67e41f13b60c62.html"),
            Channel("广西", "新闻频道", "https://tv.gxtv.cn/channel/channelivePlay_9dfd8600075811e9ba67e41f13b60c62.html"),
            Channel("广西", "国际频道", "https://tv.gxtv.cn/channel/channelivePlay_bfa17b64157f11e999f0e41f13b60c62.html"),
            Channel("广西", "乐思购", "https://tv.gxtv.cn/channel/channelivePlay_ed58bc4a207811e999f0e41f13b60c62.html"),
            Channel("广西", "移动数字电视频道", "https://tv.gxtv.cn/channel/channelivePlay_78dbfd44e6b74ab687204d2d8113cbf5.html"),
            Channel("广西", "CETV-1", "https://tv.gxtv.cn/channel/channelivePlay_ffa6b6e1b32b4a16a73eb3ef66f8bfc7.html"),
            Channel("广西", "CETV-2", "https://tv.gxtv.cn/channel/channelivePlay_80d0ffb42c114eaf9663708629ff0a3e.html"),
            Channel("广西", "CETV-4", "https://tv.gxtv.cn/channel/channelivePlay_67eace939278435bb4bca90800fb4225.html")
        )

        /** 各省卫视官网直播页（已按要求移除，保留空表避免改动播种结构）。 */
        private val WEISHI = listOf<Channel>()

        /**
         * 深圳广电（sztv.com.cn/dianshi.shtml）的 8 个直播频道。
         * 每个频道有独立 URL（?id=），点频道条是 window.open 换 id，无需脚本定位。
         */
        private val SZTV = listOf(
            Channel("深圳", "深圳卫视4K超高清", "https://www.sztv.com.cn/dianshi.shtml?id=24725"),
            Channel("深圳", "深圳卫视", "https://www.sztv.com.cn/dianshi.shtml?id=7867"),
            Channel("深圳", "都市频道", "https://www.sztv.com.cn/dianshi.shtml?id=7868"),
            Channel("深圳", "电视剧频道", "https://www.sztv.com.cn/dianshi.shtml?id=7880"),
            Channel("深圳", "少儿频道", "https://www.sztv.com.cn/dianshi.shtml?id=7881"),
            Channel("深圳", "移动电视", "https://www.sztv.com.cn/dianshi.shtml?id=7869"),
            Channel("深圳", "宜和购物", "https://www.sztv.com.cn/dianshi.shtml?id=7878"),
            Channel("深圳", "国际频道", "https://www.sztv.com.cn/dianshi.shtml?id=7944")
        )

        /**
         * 芒果TV 直播页（mgtv.com/live）的频道。
         * 侧栏频道是 JS 点击切换，没有独立 URL；链接带 ch=（频道名）+ cat=（分类页签）
         * 标记目标，由 MGTV 脚本先切到对应分类页签，再点同名频道。
         * 分类页签：热门 / 卫视台 / 地方台 / 特色频道。
         */
        private val MGTV = listOf(
            Triple("金鹰卡通", "热门", "287"),
            Triple("快乐购", "热门", "267"),
            Triple("湖南经视", "热门", "280"),
            Triple("湖南娱乐", "热门", "344"),
            Triple("湖南电影", "热门", "221"),
            Triple("湖南都市", "热门", "346"),
            Triple("湖南电视剧", "热门", "484"),
            Triple("金鹰纪实", "热门", "316"),
            Triple("湖南爱晚", "热门", "261"),
            Triple("国际频道", "卫视台", "229"),
            Triple("快乐垂钓", "特色频道", "218"),
            Triple("长沙新闻综合", "特色频道", "269"),
            Triple("长沙政法", "特色频道", "254")
        ).map {
            Channel(
                "芒果TV", it.first,
                "https://www.mgtv.com/live/?ch=" + java.net.URLEncoder.encode(it.first, "UTF-8") +
                    "&cat=" + java.net.URLEncoder.encode(it.second, "UTF-8") +
                    "&sid=" + it.third
            )
        }

        /**
         * 看看新闻「看电视」页的 8 个频道。
         * 该站切换频道是点击页底频道栏的 li，网址始终停在 /huikan，没有独立频道页；
         * 真实 m3u8 带 35 分钟过期的 token，不能直接当频道地址。
         * 因此链接里带 ch=（频道名），由注入脚本在页面加载后模拟点击对应频道。
         */
        private val KANKAN = listOf(
            "东方卫视", "新闻综合", "魔都眼", "第一财经",
            "新纪实", "五星体育", "都市频道", "哈哈炫动"
        ).map {
            Channel(
                "看看新闻", it,
                "https://live.kankanews.com/huikan?ch=" + java.net.URLEncoder.encode(it, "UTF-8")
            )
        }

        /**
         * oxax.tv 的 40 个频道（站点 porno-kanaly.html 分类页，每频道一个独立页面）。
         * 站点播放器是页面内 MSE(blob) 视频，走默认 AUTOPLAY_JS 即可。
         * 注意必须用 http：该站未提供可靠的 https 证书。
         */
        private val OXAX = listOf(
            "oh-ah.html" to "ОХ-АХ HD",
            "superone-hd.html" to "SuperOne HD",
            "sl-hot1.html" to "CineMan XXX HD",
            "sl-hot2.html" to "CineMan XXX2 HD",
            "brazzers-tv-europe.html" to "Brazzers TV Europe",
            "brazzers-tv.html" to "Brazzers TV",
            "red-lips.html" to "Red Lips",
            "kino-xxx.html" to "KinoXXX",
            "xy-max-hd.html" to "XY Max HD",
            "xy-plus-hd.html" to "XY Plus HD",
            "xy-mix-hd.html" to "XY Mix HD",
            "barely-legal.html" to "Barely legal",
            "vivid-red.html" to "Vivid Red HD",
            "hot-pleasure.html" to "Exxxotica HD",
            "babes-tv.html" to "Babes TV",
            "russkaya-noch.html" to "Русская ночь",
            "pink-o.html" to "Pink O TV",
            "erox-hd.html" to "Erox HD",
            "eroxxx-hd.html" to "Eroxxx HD",
            "hustler-hd.html" to "Hustler HD",
            "private-tv.html" to "Private TV",
            "redlight-hd.html" to "Redlight HD",
            "penthouse-gold.html" to "Penthouse Gold HD",
            "penthouse-2.html" to "Penthouse Quickies",
            "o-la-la.html" to "O-la-la",
            "blue-hustler.html" to "Blue Hustler",
            "shalun.html" to "Шалун",
            "dorcel-tv.html" to "Dorcel TV",
            "extasyhd.html" to "Extasy HD",
            "xxl.html" to "XXL",
            "fap-tv-2.html" to "FAP TV 2",
            "fap-tv-3.html" to "FAP TV 3",
            "fap-tv-4.html" to "FAP TV 4",
            "fap-tv-parody.html" to "FAP TV Parody",
            "fap-tv-compilation.html" to "FAP TV Compilation",
            "fap-tv-anal.html" to "FAP TV Anal",
            "fap-tv-teens.html" to "FAP TV Teens",
            "fap-tv-lesbian.html" to "FAP TV Lesbian",
            "fap-tv-bbw.html" to "FAP TV BBW",
            // fap-tv-trans 已 404，不再收录
        ).map { Channel("OXAX", it.second, "http://oxax.tv/" + it.first) }

        /**
         * 海南网络广播电视台 7 个电视频道。
         * 频道列表来自 https://www.hnntv.cn/api/channel?type=1 ，页面 URL 带 channelId 直接起播，
         * 与已有 HNNT_JS 注入脚本对应（video.js 播放器）。
         */
        private val HAINAN = listOf(
            Channel("海南", "海南卫视", "https://www.hnntv.cn/live.html?playType=livePlay&channelId=13&referPage=home"),
            Channel("海南", "三沙卫视", "https://www.hnntv.cn/live.html?playType=livePlay&channelId=5&referPage=home"),
            Channel("海南", "海南自贸", "https://www.hnntv.cn/live.html?playType=livePlay&channelId=1&referPage=home"),
            Channel("海南", "海南新闻", "https://www.hnntv.cn/live.html?playType=livePlay&channelId=3&referPage=home"),
            Channel("海南", "海南社会与法", "https://www.hnntv.cn/live.html?playType=livePlay&channelId=4&referPage=home"),
            Channel("海南", "海南文旅", "https://www.hnntv.cn/live.html?playType=livePlay&channelId=6&referPage=home"),
            Channel("海南", "海南少儿", "https://www.hnntv.cn/live.html?playType=livePlay&channelId=7&referPage=home")
        )

        /**
         * 河北台 7 个电视频道（www.hebrts.cn 单页，index=0~6 换台）。
         * 顺序与站点页面右侧频道条一致，index 就是列表下标；
         * 播放器是 cmcPlayer（腾讯云 TGP），链路 #cmc_playercontainer > .cmc-player。
         */
        private val HEBEI = listOf(
            Channel("河北", "河北卫视", "https://www.hebrts.cn/19/19js/st/xdszb/index.shtml?index=0"),
            Channel("河北", "经济生活", "https://www.hebrts.cn/19/19js/st/xdszb/index.shtml?index=1"),
            Channel("河北", "河北都市", "https://www.hebrts.cn/19/19js/st/xdszb/index.shtml?index=2"),
            Channel("河北", "文旅体育", "https://www.hebrts.cn/19/19js/st/xdszb/index.shtml?index=3"),
            Channel("河北", "少儿科教", "https://www.hebrts.cn/19/19js/st/xdszb/index.shtml?index=4"),
            Channel("河北", "三农频道", "https://www.hebrts.cn/19/19js/st/xdszb/index.shtml?index=5"),
            Channel("河北", "三佳购物", "https://www.hebrts.cn/19/19js/st/xdszb/index.shtml?index=6")
        )

        /**
         * 福建台 6 个电视频道（live.fjtv.net，每个频道一个独立页面）。
         * 播放器是阿里云 Aliplayer，直接走通用 WebView 注入即可。
         */
        private val FUJIAN = listOf(
            Channel("福建", "东南卫视", "https://live.fjtv.net/setv/"),
            Channel("福建", "综合频道", "https://live.fjtv.net/zhpd/"),
            Channel("福建", "新闻频道", "https://live.fjtv.net/xwpd/"),
            Channel("福建", "文旅体育", "https://live.fjtv.net/dspd/"),
            Channel("福建", "少儿频道", "https://live.fjtv.net/sepd/"),
            Channel("福建", "海峡卫视", "https://live.fjtv.net/hxtv/")
        )

        /**
         * 云南台 4 个电视频道（www.yntv.cn/live.html，单页换台）。
         * 页面自己会读 ?name= 参数调 jmd3(name) 起播，无需脚本点击；
         * 频道标识另有 yunnanyule / yunnanshaoer / yunnanshenghuo 三个，
         * 站点已把它们注释停播（m3u8 返回 404），故不收录。
         */
        private val YUNNAN = listOf(
            Channel("云南", "云南卫视", "https://www.yntv.cn/live.html?name=yunnanweishi"),
            Channel("云南", "云南都市", "https://www.yntv.cn/live.html?name=yunnandushi"),
            Channel("云南", "康旅频道", "https://www.yntv.cn/live.html?name=yunnangonggong"),
            Channel("云南", "澜湄国际", "https://www.yntv.cn/live.html?name=yunnanguoji")
        )

        /** 全部内置频道（扁平表），内置频道不可编辑、不可删除。 */
        private val BUILTINS: List<Channel>
            get() = BUILTIN_GROUPS.flatMap { it.third }

        /** 分组 + 是否覆盖修正已有链接 + 各自的播种标记。 */
        val BUILTIN_GROUPS = listOf(
            // v1：央视网直播，全国频道放在最前
            Triple("seeded_builtins_cctv_v1", true, CCTV),
            Triple(KEY_SEEDED, false, SHANDONG),
            // v2：深圳卫视 + 广东/浙江/河南卫视（各省官网直播页）
            Triple("seeded_builtins_sztv_v2", true, WEISHI),
            // v5：江苏卫视替换为 4K 超高清（站点动态频道，按名称定位）
            Triple("seeded_builtins_jiangsu_v5", true, JIANGSU),
            // v6：看看新闻 8 个电视频道（页面底部频道栏，按名称点击切换）
            Triple("seeded_builtins_kankan_v6", true, KANKAN),
            // v7：芒果TV 直播页 13 个频道（分类页签 + 侧栏点击切换）
            Triple("seeded_builtins_mgtv_v7", true, MGTV),
            // v8：浙江广电 10 个频道（CDN 防盗链，播放时现算 auth_key）
            Triple("seeded_builtins_cztv_v8", true, ZHEJIANG),
            // v9：浙江改走站点直播页（直链交给原生播放器会崩溃）
            Triple("seeded_builtins_cztv_v9", true, ZHEJIANG),
            // v10：深圳广电 8 个频道（dianshi.shtml?id=，各自独立 URL）
            Triple("seeded_builtins_sztv_v10", true, SZTV),
            // v11：OXAX 已清空（分类保留为空，见 grouped()），不再播种
            // v12：广东台 18 个电视频道（荔枝网 gdtv.cn 各频道独立直播页）
            Triple("seeded_builtins_gdtv_v12", true, GUANGDONG),
            // v13：江西台 8 个电视频道（jxntv.cn 单页点频道条换台）
            Triple("seeded_builtins_jxntv_v13", true, JXNTV),
            // v14：广西台 11 个电视频道（tv.gxtv.cn 各频道独立直播页）
            Triple("seeded_builtins_gxtv_v14", true, GXTV),
            // v15：海南台 7 个电视频道（hnntv.cn 单页 channelId 参数换频道）
            Triple("seeded_builtins_hnnt_v15", true, HAINAN),
            // v16：河北台 7 个电视频道（hebrts.cn 单页 index=0~6 换频道）
            Triple("seeded_builtins_hebei_v16", true, HEBEI),
            // v17：福建台 6 个电视频道（live.fjtv.net 各频道独立页面，走通用 WebView）
            Triple("seeded_builtins_fujian_v17", true, FUJIAN),
            // v18：云南台 4 个电视频道（yntv.cn 单页 ?name= 参数换频道）
            Triple("seeded_builtins_yunnan_v18", true, YUNNAN),
            // v19：央视频 33 个卫视频道（tv/home?pid= 各频道直达）
            Triple("seeded_builtins_ysp_weishi_v19", true, WEISHI_YSP),
            // v20：央视频补全央视 20 个频道（CGTN 各语种 / 剧场 / 专业 / 8K）
            Triple("seeded_builtins_ysp_cctv_v20", true, CCTV_YSP),
            // v21：B站直播频道
            Triple("seeded_builtins_bilibili_v21", true, BILIBILI)
        )
    }
}
