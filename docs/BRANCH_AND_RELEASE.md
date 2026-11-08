# 分支与打包规矩

> 2026-11-08 定。起因：改动分散在多条分支上，多次出现「源码改了、打出来的包却不含改动」。

## 一、主线只有一条

```
test/beta-on-latest     ← 唯一主线，正式版与测试版都从这里打
main                    ← 只作上游同步基准（同步 Mangi-11/Eta），不放开发内容
```

`main` 与主线的关系：主线包含 `main` 的全部提交（`git merge-base --is-ancestor main test/beta-on-latest` 恒为真）。

## 二、四条规矩

1. **功能分支完成即合并**。任何 `feat/*`、`fix/*` 分支上的工作一旦可用，立刻合入 `test/beta-on-latest`，
   不要留在分支上等。不允许「先放着，下次一起合」。
2. **打包只从主线**。不得在功能分支上 `assembleRelease`。
3. **打包前自检三项**，缺一不可：

   ```bash
   cd /workspace/项目/Eta/Eta主源码
   git branch --show-current      # 必须是 test/beta-on-latest
   git status --short | wc -l     # 必须是 0
   git log --oneline -1           # 确认这个提交含本次要发的功能
   ```

4. **分支不留存**。合并完成后删除功能分支（`git branch -d`），只保留 `test/beta-on-latest` 与 `main`。

## 三、踩过的坑（为什么要立这条规矩）

| 时间 | 现象 | 根因 |
|---|---|---|
| 2026-11-05 | 装了 2026110501，赞赏页功能「生效」，但后续会话打的包不含它 | 提交在 `feat/support-page-and-provider-presets`，`test/beta-on-latest` 从未包含 |
| 2026-11-08 | 用户反馈「其他会话打包的版本不包含我的更改」 | 同上；且工作区未提交部分被后续切分支清掉 |
| 2026-11-08 | 打包时发现已提交代码引用了未入库的 `AppForeground.kt` | 功能分支提交不完整，纯提交的树编译不过 |

共同点：**判断「改动有没有进主线」不能看工作区文件，要看目标分支的 `git log` / `rev-list` 对比。**

## 四、合并时的注意事项

- 共享工作区里常有多个会话并行改同一批文件。合并前先用
  `git diff --name-only <目标分支> <来源分支>` 看重叠文件，逐个判断冲突语义，
  不要整文件 `--ours` / `--theirs` 一把梭。
- `strings.xml` 在多方合并时会产生重复 key（同一个 name 出现两次），资源合并阶段才报错，
  合并后必须跑一次去重检查：

  ```bash
  python3 -c "
  import io,re,collections
  s=io.open('app/src/main/res/values/strings.xml',encoding='utf-8').read()
  keys=re.findall(r'<string name=\"([^\"]+)\"',s)
  print([k for k,c in collections.Counter(keys).items() if c>1])
  "
  ```

- 任何合并后必须 `compileDebugKotlin` 通过才能算完成。
