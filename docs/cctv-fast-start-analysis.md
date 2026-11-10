# CCTV 电视壳（xiao.bu.tv）秒播机制分析

> 分析对象：`/storage/emulated/0/MT2/mcp/` 下 APK，拆包产物在 `.tmpwork/`。
> 结论：秒播是 **5 层接力** 的结果，任何单点优化都做不到同样的体感。

## 0. 技术底座

| 项 | 内容 |
|---|---|
| 播放内核 | IJK（`libijkplayer.so` / `libijkffmpeg.so` / `libijksdl.so`），**不是 ExoPlayer** |
| 自研 so | `libntvframe.so`（送帧）、`libntvquickjs.so`（页面注入）、`libntvtls.so`（TLS）、`libBugly_Native.so` |
| 页面承载 | WebView + 注入 JS 作为 UI，原生只做播放与代理 |
| 频道配置 | `assets/china_mainland.dat` / `server_config.dat` / `webpage_adapter.dat` 全部**加密压缩**，服务端可下发 |

## 1. 启动闸门 gate —— 不打无准备的仗

注入脚本里能看到门控表达式：

```js
(function(){return !!document.body && (document.readyState==='interactive'||document.readyState==='complete')})()
```

- `genericStartupPrefetch=` 控制「播放器未就绪，先不请求流」
- `prefetchThreads=1`（**单线程**，避免并发抢占首帧带宽）
- `statefulSession=` 复用会话来跳过重复握手
- 运行时日志：`CCTV startup gate ready queued=`、`CCTV startup invalidated`、`Channel catalog ready in ...`

**要点**：切台时不是立刻拉 m3u8，而是先等 channel catalog + 播放器实例就绪，把「白屏排队」变成「并行准备」，看起来就是一点就播。

## 2. 流到达前的准备：本地代理 + 明文短路

- `HlsProxyServer`：起本地 http 端口，把 CBS/CCTV 的分片解密、拼接后喂给 IJK（`CCTV pipeline segment=`、`CCTV decrypt profile parallel=`）
- 但明文流**不绕代理**：

```
'Clear HTTP HLS handed directly to IJK:
```

  能直连的流直接给播放器，省掉一次本地代理往返（这是首帧省几百 ms 的关键取舍）。

## 3. 分段预取：边播边备下一段

日志证据：`CCTV playlist prefetch poll skipped`、`CCTV segment attempt`、`CCTV segment recovered on attempt`、`CCTV segment prefetch exhausted`。

播放当前 TS 段的同时，后台按 m3u8 预取后续分段并写缓存；断流时用 `exhausted` 触发重试而不是黑屏等待。

## 4. 硬解 + 直渲（体感最快的部分）

- 默认走 MediaCodec：`Default MediaCodec=` / `Forcing MediaCodec=`，设置项 `hardwareDecoder`、`hardwareDecoders`、`playerEngine`
- 自研 `NativeVideoFrame` / `NativeHdrOutput` + `libntvframe.so`：解码后的帧**直接交给系统渲染层**，绕开 IJK 自带的 SDL 软件渲染
- 友商定制类 `IjkCodecPerformance` 针对盒子芯片做过调优（源码里大量 Amlogic/海思特征串）

## 5. 缓存与回退

`LegacyWebCacheService` 承载二级缓存；异常时 `$Cached engine failed, retry upstream` 直接回落源站，不把错误抛给用户。

---

## 可移植性判断（给本项目 com.llq.hello）

| 手段 | 成本 | 收益 | 建议 |
|---|---|---|---|
| 启动闸门（先建实例后拉流） | 低 | 高 | ✅ 优先做 |
| 明文流直连、仅加密流走代理 | 中 | 高 | ✅ 视现有 StreamResolver 结构 |
| 分段预取（1 线程） | 中 | 中高 | ✅ |
| MediaCodec 硬解 | 低 | 高 | ✅ 已有 IJK 则改参数 |
| 自研 native 送帧 so | 极高 | 中 | ❌ 不建议 |
| 加密 assets 配置 | 低 | 低 | ⚪ 按需 |

---

## 附：央视频（yangshipin）直链可行性实测（2026-11-05）

实测 `pid=600002309`，用来判断「能否绕开 WebView 由原生直接播」：

| 验证项 | 结果 | 结论 |
|---|---|---|
| 浏览器里抓到的 `live-dtocnc-cdn.ysp.cctv.cn/<HASH>/2024xxxx_web.m3u8` 裸 GET（不带任何 query 签名） | **200**，返回标准 HLS 清单，仅 3 个分片（EXT-X-TARGETDURATION 很短） | 分片少 + 无签名 → 起播天然快，这是「秒播」的物理基础 |
| 把地址里的 HASH 段替换成全 0 再请求 | **403**（nginx，`The access control configuration prevents your request`） | HASH 段本身**就是鉴权令牌**，不是路径装饰；无法靠 pid 推算 |
| `GET/POST https://player-api.yangshipin.cn/v1/player/get_live_info` | GET → `{"code":10016,"msg":"路由不存在"}`；POST `{"pid":...}` → **401** 空响应体 | 取流接口必须带签名头 |

401 的响应头暴露了它要哪些头：

```
Access-Control-Allow-Headers: ..., platform, yspappid, yspPlayerToken, yspsdkinput, yspsdksign, request-id, seqId, yspticket
```

页面实际加载的是 `https://sapi.yangshipin.cn/assets/2025/wasm/hls.cmg.js` —— 即
**签名由 wasm 模块现算**（js 侧调用 wasm 生成 `yspsdksign`/`yspsdkinput`）。

由此得出的取舍（重要，避免再花时间走死路）：

1. 央视频**不能**「拿 pid 拼 URL」直接播，HASH 路径只能通过签名接口换取；
2. 纯 Kotlin 复刻 wasm 签名不现实（体积大、易随版本变），且违反最小有效修改原则；
3. 若确实要接央视频，只有两条路：
   - **WebView 内取流**：页面播放器跑起来后用 `shouldInterceptRequest` 截 `*.m3u8`，
     把地址交给原生 ExoPlayer —— 复用站点 wasm 签名，零逆向成本；
   - **打包 wasm**：把 `hls.cmg.js` + wasm 塞进 assets，用 Android WebView 后台实例算签名 —— 工程量大、易失效。
4. 若目标是「自己控播放器、自己控起播」，本项目的**深圳卫视 Resolver 路线**才是对的：
   签名算法已知、可离线算出、地址直接喂 ExoPlayer。

### 结论：本项目该怎么做到同样的「秒播」

已落地的三点（`VideoPlayerActivity`）——启动与取流并行、起播缓冲 800ms、起播钉直播边缘——
就是 CCTV 那套 5 层里成本最低、收益最高的第 1 层「启动闸门」的等价物。
再要提升，按性价比排序应做：

1. **切台复用同一 ExoPlayer 实例**（`setMediaItem` 换源而非重建 Activity）——这是体感提升最大的一步；
2. **预取下一台地址**（在列表页就后台解析，点进去时地址已在手）；
3. 分段预取（ExoPlayer 自带 HLS 分片预取，收益有限，最后再考虑）；
4. HLS 本地代理：仅在遇到加密/防盗链分片时才做，明文流直连更快（与 CCTV 的
   `Clear HTTP HLS handed directly to IJK` 完全同思路）。