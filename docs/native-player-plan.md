# 原生直播流播放器方案（MyAgent 项目 com.llq.hello）

目标：不再依赖各站点 WebView 播放器，自己从官方接口拉流、原生解码播放；
流地址带时效签名，到期前自动续签换源，实现长时间无断点观看。

---

## 一、总体架构

```
┌─ ChannelRepo ──────── 频道表（频道 → 官方页面/接口）
│        │
├─ StreamResolver ───── 取流器（每个台一套实现）
│    │  · 走官方接口拿 m3u8 + 签名（复用已还原的算法）
│    │  · 输出带时效的 ResolvedStream{url, expireAt}
│    ▼
├─ StreamKeeper ─────── 保活层（核心新增）
│    │  · 每 30s 检查 expireAt，剩 5 分钟时预取新地址
│    │  · 播放器无感换源：靠 hls 直播 playlist 的「滑动窗口」
│    │    或在分片边界 seek 到新地址继续
│    ▼
├─ PlayerCore ────────── Media3/ExoPlayer
│    │  · HLS 直播：DefaultLoadControl + live 参数调优
│    │  · 出错重试：DefaultRenderersFactory + LoadErrorHandlingPolicy
│    ▼
└─ UI 层：频道列表 + 播放 SurfaceView + 状态条
```

## 二、技术选型（关键决策）

**播放内核：Media3 ExoPlayer（androidx.media3:media3-exoplayer + media3-exoplayer-hls + media3-ui）**

理由：
- 原生 HLS 支持（含直播滑动窗口、EXT-X-DISCONTINUITY 处理），不引 ffmpeg/ijk 那种几十 MB 的依赖
- `setMediaItem` 换源成本低，配合「同 playlist 重新加载」可做到不闪黑屏
- 依赖池用 fetch_dep 就能拉齐（media3 三件套 + 它的传递依赖 media3-common等，纯 jar/aar，无 so，设备内构建无压力）

**备选（不推荐先做）**：继续 WebView + 自己解析流。优点是复用站点播放器的防盗链处理；缺点是受制于站点 JS，正是本次要摆脱的。

## 三、取流层设计（StreamResolver）

每个频道一个 `Resolver` 接口实现：

```kotlin
interface StreamResolver {
    /** 返回带时效的流地址；expireAt 由签名算法算出 */
    suspend fun resolve(): ResolvedStream
}
data class ResolvedStream(val url: String, val expireAt: Long, val headers: Map<String,String> = emptyMap())
```

**深圳卫视（已逆向还原，作为第一个实现样板）：**
1. `GET https://www.sztv.com.cn/dianshi.shtml?id=<liveId>` → 正则抓 `/pindao/index.html?liveId=xxx` → iframe 页
2. 授权接口：`GET {api}?t=<当前秒>&id=<liveId>&token=md5(秒+liveId+"ejow…g7"密钥)&at=1` → 返回密文
3. 密文 Z 解码（前后半对调→字符串反转→Base64）= 清晰度目录（如 `500`）
4. 拼流：`https://sztv-live.sztv.com.cn/<liveId>/<流ID>/<aa>.m3u8`
   （流 ID 也从授权链路/页面里拿，如 `q7A7e0R`）
5. 签名：`?sign=md5(密钥+路径)&t=<now+1h 的十六进制>` → **expireAt = now + 1h**
6. 请求带 `Referer: https://www.sztv.com.cn/`（防盗链）

**其他频道**：广东 GDTV / 湖南 HNNT / 河北 HEBEI 等逐台逆向，一个 Resolver 一个类；
接口不通的台降级为「WebView 兜底」（现有 WebView 逻辑保留为 fallback）。

## 四、保活层设计（StreamKeeper，最核心）

```
循环（每 30s）:
  if 播放中 && 剩余时效 < 5min:
      newStream = resolver.resolve()          // 预取新签名地址
      if 换源失败 or 连续3次失败: 通知 UI 显示「重连中」
  if 播放器 STATE_IDLE/报错:
      用最新地址重建 MediaItem → player.prepare()
```

要点：
- **直播 m3u8 是滑动窗口**：同一 media playlist 的分片在滚动，续签只是换 query 里的 sign/t，
  路径不变 → 直接 `player.setMediaItem(newItem, /* startPositionMs= */ C.TIME_UNSET)`
  对 HLS 直播，ExoPlayer 会复用同一 playlist 上下文，**无需打断播放**；
  若路径变了（罕见），则在下一个分片边界 seek 后换源。
- 失败退避：指数退避 1s/2s/4s…上限 30s，连续 5 次失败回退 WebView 兜底
- 保活定时器用 `Handler.postDelayed`（主线程）或协程 `while(isActive)`，随播放器释放而取消

## 五、播放内核配置要点

```kotlin
val player = ExoPlayer.Builder(context)
    .setLoadControl(DefaultLoadControl.Builder()
        .setBufferDurationsMs(15000, 50000, 1000, 2000)  // 直播低延迟
        .build())
    .build()
player.setMediaItem(MediaItem.Builder()
    .setUri(stream.url)
    .setMimeType(MimeTypes.APPLICATION_M3U8)
    .build())
player.prepare(); player.play()
```

- 音频先响视频黑屏（GOP 问题）原生下不明显：ExoPlayer 从 playlist 最新分片组开始，
  天然对齐关键帧；若仍需加速首帧，`player.seekToDefaultPosition()` 或设置
  `HlsMediaSource` 的 `allowChunklessPreparation=true`

## 六、UI 结构（最小可用）

- `PlayerActivity`：全屏 SurfaceView（PlayerView），上滑/点击呼出频道侧栏
- 频道表：JSON 资源文件（`assets/channels.json`），条目 = {name, resolverType, liveId, webViewFallback}
- 状态提示：右上角小圆点 + 文案（直播中 / 换源中 / 已回退 WebView）
- 现有 MainActivity 的 WebView 逻辑不动，新增入口跳 PlayerActivity（灰度过渡，可随时回退）

## 七、实施步骤（每步可单独构建验证）

| # | 内容 | 验证方式 |
|---|------|---------|
| 1 | fetch_dep 拉 media3 三件套 + deps.txt 记录 | prepare_build 通过 |
| 2 | StreamResolver 深圳实现 + 纯 JVM 单测（Z解码、md5签名、URL拼接） | run_tests |
| 3 | StreamKeeper（30s tick + 预取换源 + 退避） | run_tests（时间可注入） |
| 4 | PlayerActivity 播放深圳卫视真流 | build_app 装机真测 |
| 5 | 逐台补 Resolver（广东/湖南/河北…） | 每台装机点测 |
| 6 | WebView 兜底回退 + UI 打磨 | 全量回归 |

## 八、风险与对策

- **流 ID/清晰度可能变**：Resolver 每次全流程重新走（不缓存路径），失败自动重试
- **官方接口改版**：单台失效只影响该台；靠 Resolver 接口隔离，改一处
- **Referer/UA 防盗链**：ResolvedStream.headers 透传给 ExoPlayer（DefaultHttpDataSource 设置）
- **后台播放**：如需锁屏续播，后续加 ForegroundService（本期不做，先保前台体验）
