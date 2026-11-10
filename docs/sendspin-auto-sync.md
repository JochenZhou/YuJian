# Sendspin 自动同步实验分支

`feature/sendspin-auto-sync` / `1.6.2-auto-sync`（versionCode 43）在官方 sendspin-jvm 协议、PCM16 解码和 Kalman 时钟之上，增加 Android 输出同步闭环。支持 Android 6 / API 23。

## 工作方式

- 首批八次探测选出的有效测量处理完成后，才开始音频调度。
- 保留音频服务器时间戳和每次真正写入的输出帧范围。部分写入、补静音和重采样都计入同一时间线。
- 优先使用通过推进、时间和范围校验的 `AudioTimestamp`，其次使用实际播放头；暂时没有可靠数据时使用受已提交帧数限制的估算。暂停、断流不会凭空增加已播放帧。AudioTrack 缓冲占用使用独立的消费位置计算，避免把正常 DAC 下游延迟误判为缓冲积压。
- 格式切换在 WebSocket 消息边界先暂停旧消费者，再清理旧队列并配置新输出，保留随后到达的新格式数据。
- 首帧先满足设备报告的最小缓冲和实际分配容量，再按目标本地时间启动 AudioTrack；旧 AudioFlinger 的 FS_FILLING 需要实际容量填满。输出队列上限取 80ms、设备最小缓冲的 1.5 倍和实际容量中的最大值，并通过时间戳提前提交，避免把预缓冲变成播放延迟。继续使用在小米 Android 6 上验证可出声的旧 `STREAM_MUSIC` 构造器，同格式切换复用音轨，降低电视 HAL 重建瞬态。
- 播放重启和硬裁剪边界采用 4ms raised-cosine 过渡，仅覆盖现有采样，不添加音频帧或移动时间戳。普通重采样速率回到 1.0 时保留相位，真正切流/裁剪时显式重置。
- 自动模式下，有可靠位置反馈时，小误差用最大 ±0.5% 的线性重采样缓慢纠正。较早的间隙补静音，过期音频按帧裁剪，较大不连续、欠载和焦点恢复重新对齐时间线。
- 硬件反馈暴露积压或写入停滞时，丢弃本地过期输出，有限次数重建音轨。

## 电视播放器

原生横屏双栏展示大封面、曲目信息、动态进度和主要播放控件。轻量的 125ms 显示刷新使用服务器进度、时间戳和速度组成的锚点；暂停冻结、seek/曲目切换重新锚定。Runtime 250ms 推送让 HTTP 和电视界面使用同一进度来源。元数据部分更新保留整个有效锚点，确认切歌或会话变化时清除上一首的进度。

封面仅在内容改变时后台解码，遥控器焦点有描边和轻微缩放，进度条支持方向键和确认提交。播放器不使用高版本模糊 API。

## Web 控制台

在“Sendspin 音乐”中选择“自动同步”，点击“应用同步设置”。本分支默认自动模式，既有的手动延迟保留但不会叠加到自动调度。切到手动模式后可以恢复原有补偿值；明确下发 `output_delay_ms`（包括 0）会选择手动模式。

状态显示时钟准备情况、位置来源、同步误差和恢复次数。正误差表示预计输出偏晚。`timestamp` / `playback_head` 表示硬件位置来源，`estimate` 表示降级估算，`unavailable` 表示暂无位置。误差是客户端相对于驱动报告位置的时间线误差，不是麦克风测得的扬声器声学误差。降级估算不宣称硬件已同步。

现有 `/{token}/sendspin` 接口增加：

```json
{"action":"config","sync_mode":"auto"}
```

```json
{"action":"config","sync_mode":"manual","output_delay_ms":800}
```

GET 状态包含 `sync_mode`、`manual_output_delay_ms`、实际使用的 `output_delay_ms`、`clock_ready`、`sync_state`、`position_source`、可为 null 的 `sync_error_ms`、`resync_count`、`underrun_count`、`inserted_frames` 和 `dropped_frames`。配置可在未连接时保存。未知误差保持 null。

## 验证

```text
gradle :sendspin-protocol:test :app:testDebugUnitTest --no-daemon
node --test app/src/test/js/sendspin-console.test.cjs
gradle :app:assembleDebug :app:lintDebug :app:assembleRelease --no-daemon
```

确定性测试覆盖启动锚点、时间戳暂不可用、异常回退与真正回绕、欠载、焦点暂停恢复、部分写入、过期块裁剪、服务器时钟准备、模式迁移，以及模拟正负硬件漂移。GitHub Actions 对本分支执行这些检查，单元测试失败会阻断 APK 交付。

## 限制和参考

不包含麦克风声学校准。如果厂商 HAL 始终不提供可信位置，软件只能降级估计，无法独立测出电视 DSP、HDMI 接收端或外部音箱隐藏的延迟。需要在实际设备上验证同组播放、暂停/切歌、网络波动恢复和长时间漂移；模拟测试不能代替听感或录音测量。

算法设计参考 sendspin-cpp `ef1157f0525d2107320736efc0909d78832cc270` 的输出帧反馈，以及 MassDroid Native `e11566ba0e42a686506d184fb110a855c61729ea` 的 PCM 样本时间映射。没有引入其原生引擎或复制 Ava-Pro 的实现代码；官方 JVM 库来源和本地补丁记录在 `third_party/sendspin-jvm/UPSTREAM.md`。
