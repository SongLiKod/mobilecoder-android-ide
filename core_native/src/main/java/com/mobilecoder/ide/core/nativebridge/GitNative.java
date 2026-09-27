package com.mobilecoder.ide.core.nativebridge;

/**
 * Git 引擎 JNI 绑定（libgit2 静态编译，实现见 core_native/src/main/cpp/git_jni.c）。
 *
 * 返回约定（与 C 层一致，见 git_jni.c 头部注释）：
 *  - boolean 方法：true 成功 / false 失败（错误详情 {@link #lastError()}）
 *  - int 状态方法：0 成功；1 冲突/需人工处理；2 已是最新/无变更；-1 错误
 *  - String 列表：记录以 '\n' 分隔，字段以 '\x01' 分隔
 *
 * 注意：native 层内部只维护**一个**仓库句柄（g_repo），
 * 因此同一时刻只能打开一个仓库；切换仓库前调用 {@link #closeRepo()}。
 */
public final class GitNative {

    static {
        System.loadLibrary("mobilecoder");
    }

    private GitNative() {
    }

    /* ---------------- 生命周期 ---------------- */

    public static native void registerProgressCallback(GitProgressCallback callback);

    /** libgit2 初始化（引用计数）。 */
    public static native boolean runtimeInit();

    /** 设置 HTTPS CA 证书文件路径（解压后的 cacert.pem）。 */
    public static native void setCertificateFile(String path);

    /**
     * 配置网络凭据（HTTPS 基本认证 + SSH 公钥认证），空串表示该类凭据为空。
     * SSH 私钥直接以内存凭据传入，不落盘。
     */
    public static native void setCredentials(
            String username, String password,
            String sshUsername, String privateKey, String publicKey, String passphrase);

    public static native void clearCredentials();

    public static native void shutdown();

    /** 关闭当前仓库句柄（切换仓库前调用）。 */
    public static native void closeRepo();

    /** 最近一次失败的错误详情。 */
    public static native String lastError();

    /* ---------------- 仓库 ---------------- */

    public static native boolean isRepository(String path);

    public static native boolean initRepository(String path);

    public static native boolean openRepository(String path);

    /** 当前仓库工作区绝对路径（未打开返回空串）。 */
    public static native String workdirPath();

    /**
     * 克隆仓库。
     *
     * @param branch 分支，可空/空串表示默认分支
     * @return 0 成功 / -1 失败
     */
    public static native int clone(String url, String path, String branch);

    /* ---------------- 变更 ---------------- */

    /**
     * 工作区+索引状态。
     *
     * @return 每行 {@code path \x01 idxStatus \x01 wtStatus \x01 ignored}，末尾带 '\n'
     */
    public static native String statusList();

    public static native boolean stage(String path);

    public static native boolean stageAll();

    public static native boolean unstage(String path);

    public static native boolean unstageAll();

    /**
     * 还原工作区文件为索引内容（等价 {@code git restore <path>}）：
     * 丢弃未暂存的工作区改动，不影响索引。
     */
    public static native boolean restoreWorktree(String path);

    /**
     * 还原索引与工作区文件为 HEAD 内容（等价 {@code git reset --hard -- <path>}）：
     * 丢弃该路径的全部改动（含已暂存部分）。尚无首次提交时返回 false。
     */
    public static native boolean restoreToHead(String path);

    /**
     * 提交暂存区。
     *
     * @return 0 成功 / 2 没有已暂存变更 / -1 失败
     */
    public static native int commit(String message, String name, String email);

    /* ---------------- 历史 ---------------- */

    /**
     * 提交时间线。
     *
     * @return 每行 {@code oid \x01 shortOid \x01 summary \x01 author \x01 email \x01 time \x01 parents}
     */
    public static native String log(int limit);

    /* ---------------- 分支 / 标签 ---------------- */

    /** 每行 {@code name \x01 isHead \x01 upstream \x01 ahead \x01 behind}。 */
    public static native String branches();

    public static native String currentBranch();

    /** 每行 {@code branch \x01 detached \x01 unborn \x01 ahead \x01 behind}。 */
    public static native String headInfo();

    public static native boolean createBranch(String name);

    /** @return 0 成功 / 2 已经在该分支 / -1 失败 */
    public static native int checkoutBranch(String name);

    public static native boolean deleteBranch(String name);

    /** @return 0 成功 / 1 存在冲突 / 2 已是最新 / -1 失败 */
    public static native int merge(String name);

    /** @return 0 成功 / -1 失败 */
    public static native int mergeAbort();

    /** 每行 {@code name \x01 oid \x01 annotated \x01 message}。 */
    public static native String tags();

    public static native boolean createTag(String name, String message);

    public static native boolean deleteTag(String name);

    /* ---------------- Diff / 冲突 ---------------- */

    /** unified patch 文本。staged=true 取「树→索引」，false 取「索引→工作区」。 */
    public static native String diffPatch(boolean staged);

    /** 每行 {@code added \x01 deleted \x01 modified \x01 linesAdd \x01 linesDel}。 */
    public static native String diffStats();

    /** 每行 {@code path \x01 ancestorOid \x01 oursOid \x01 theirsOid}。 */
    public static native String conflicts();

    /** side: 1=祖先 2=我方 3=对方；该侧被删除时返回 null。 */
    public static native byte[] conflictContent(String path, int side);

    /** 写回工作区并标记冲突已解决（同时加入索引）。 */
    public static native boolean resolveConflictContent(String path, byte[] content);

    /** 选择某一侧内容作为解决方案。 */
    public static native boolean resolveConflict(String path, int side);

    public static native boolean hasConflicts();

    /** 冲突全部解决后生成合并提交。 @return 0 成功 / -1 失败 */
    public static native int finishMerge();

    /* ---------------- 远程 ---------------- */

    /** 每行 {@code name \x01 url}。 */
    public static native String remotes();

    public static native boolean addRemote(String name, String url);

    public static native boolean removeRemote(String name);

    public static native boolean setRemoteUrl(String name, String url);

    /** @return 0 成功 / -1 失败 */
    public static native int fetch(String name);

    /** @return 0 成功 / -1 失败 */
    public static native int push(String name, String branch);

    /**
     * 取消进行中的 fetch / push：可从任意线程调用，立即中止正在传输的网络操作
     * （CLI 面板停止按钮 / 终端 Ctrl+C）。无进行中操作时为空操作。
     */
    public static native void cancelNetwork();

    /* ---------------- 忽略规则 ---------------- */

    public static native boolean addIgnoreRule(String rule);

    public static native void clearIgnoreRules();
}
