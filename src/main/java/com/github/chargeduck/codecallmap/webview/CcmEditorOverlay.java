package com.github.chargeduck.codecallmap.webview;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.Nullable;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 把录制数据绘制到编辑器里的覆盖层。
 *
 * <h2>职责边界</h2>
 * 只做「找到方法 → 加 inlay → 记住句柄以便清理 → 响应点击」，不做数据读取
 * （那是 {@link CcmCallRecordReader}），也不做展示决策（那是 {@link CcmInlayRenderer}）。
 *
 * <h2>为什么每次都要先清空</h2>
 * 用户可能连续点时序图里不同的箭头，每次都是同一个编辑器。
 * 如果不在每次绘制前清掉上一批 inlay，灰字就会层层叠加，代码被挤得没法看。
 *
 * <h2>为什么只在行尾放一条提示</h2>
 * 早期版本把每个实参插在形参后面（{@code page(int page, int size)} 变成
 * {@code page(int page = 1, int size = 20)}）。它确实"像 debug"，
 * 但 inlay 会<b>占据真实的行内空间</b>：参数一多，整行代码被推到屏幕外，
 * 用户反馈"提示把代码遮住了"。现在参数值统一收拢到行尾一处，
 * 代码行宽度完全不受影响，完整内容点开看。
 *
 * @author chargeduck
 */
public final class CcmEditorOverlay {

    /** 当前已添加的 inlay，用于切换方法时清理。 */
    private final List<Inlay<?>> active = new ArrayList<>();

    /**
     * 已经装过点击监听的编辑器。
     *
     * <p>用 {@code WeakHashMap} 而不是普通 Map：编辑器销毁后不该再被强引用，
     * 否则打开过多少文件就会残留多少个 key。
     */
    private final Map<Editor, MouseAdapter> clickHandlers = new WeakHashMap<>();

    /**
     * 当前展示的上下文，供「翻页 / 点开详情」时重绘使用。
     */
    private Context current;

    /**
     * 在编辑器里展示某方法的某一次调用。
     *
     * @param project    项目
     * @param className  全限定类名
     * @param methodName 方法名
     * @param records    该方法的所有调用记录（按时间升序）
     * @param index      展示第几次（0-based，越界自动收敛）
     * @return true=已成功绘制；false=未找到方法或无法定位
     */
    public boolean show(Project project, String className, String methodName,
                        List<CcmCallRecord> records, int index) {
        if (project == null || project.isDisposed() || className == null || methodName == null
                || records == null || records.isEmpty()) {
            return false;
        }
        int safeIndex = Math.max(0, Math.min(index, records.size() - 1));
        CcmCallRecord record = records.get(safeIndex);

        // 第一段：读锁里取 PSI 信息（类、方法、文件、行号）
        MethodSite site = ReadAction.compute(() -> resolveSite(project, className, methodName));
        if (site == null) {
            return false;
        }

        // 第二段：EDT 上打开编辑器并加 inlay
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                return;
            }
            OpenFileDescriptor descriptor = new OpenFileDescriptor(project, site.file, site.methodOffset);
            Editor editor = openEditor(project, descriptor);
            if (editor == null) {
                return;
            }
            clearInlays(editor);
            // 记住上下文：翻页 / 点开详情时靠它重绘
            current = new Context(project, site, records, safeIndex);
            drawSummary(editor, site, record, records.size(), safeIndex);
            ensureClickHandler(editor);
            // 滚动到方法处，让用户立刻看到
            editor.getScrollingModel().scrollTo(
                    new LogicalPosition(editor.getDocument().getLineNumber(site.methodOffset), 0),
                    ScrollType.CENTER);
        });
        return true;
    }

    /**
     * 切到同一方法的指定一次调用（循环），由弹窗翻页回调触发。
     *
     * @param editor 当前编辑器
     * @param index  目标下标
     */
    public void showIndex(Editor editor, int index) {
        Context ctx = current;
        if (ctx == null || editor == null || ctx.records.isEmpty()) {
            return;
        }
        int n = ((index % ctx.records.size()) + ctx.records.size()) % ctx.records.size();
        ctx.index = n;
        clearInlays(editor);
        drawSummary(editor, ctx.site, ctx.records.get(n), ctx.records.size(), n);
    }

    /**
     * 清理当前文件里的录制 inlay。
     */
    public void clear() {
        ApplicationManager.getApplication().invokeLater(() -> {
            for (Inlay<?> inlay : new ArrayList<>(active)) {
                if (inlay != null && inlay.isValid()) {
                    inlay.dispose();
                }
            }
            active.clear();
            current = null;
        });
    }

    // ------------------------------------------------------------------
    // PSI 定位
    // ------------------------------------------------------------------

    /**
     * 定位方法在源码中的位置。
     *
     * @param project    项目
     * @param className  全限定类名
     * @param methodName 方法名
     * @return 定位结果；找不到返回 null
     */
    private static @Nullable MethodSite resolveSite(Project project, String className, String methodName) {
        PsiClass psiClass = JavaPsiFacade.getInstance(project)
                .findClass(className, GlobalSearchScope.allScope(project));
        if (psiClass == null) {
            return null;
        }
        PsiFile psiFile = psiClass.getContainingFile();
        if (psiFile == null || psiFile.getVirtualFile() == null) {
            return null;
        }
        // 同名重载可能有多个：取第一个（与既有 openMethod 的定位口径保持一致，
        // 避免"点击跳到的行"与"绘制 inlay 的行"不是同一处）
        PsiMethod target = null;
        for (PsiMethod m : psiClass.findMethodsByName(methodName, false)) {
            if (m.getTextRange() != null) {
                target = m;
                break;
            }
        }
        if (target == null) {
            return null;
        }
        Document doc = PsiDocumentManager.getInstance(project).getDocument(psiFile);
        if (doc == null) {
            return null;
        }
        int methodOffset = target.getTextOffset();
        int lineIndex = doc.getLineNumber(methodOffset);
        return new MethodSite(psiFile.getVirtualFile(), methodOffset, lineIndex,
                className + "#" + methodName);
    }

    // ------------------------------------------------------------------
    // inlay 绘制
    // ------------------------------------------------------------------

    /**
     * 在方法签名行尾画一条录制摘要（唯一的一条 inlay）。
     *
     * @param editor 编辑器
     * @param site   方法位置信息
     * @param record 本次要展示的调用记录
     * @param total  该方法总调用次数（用于「第几次」标记）
     * @param index  当前展示第几次（0-based）
     */
    private void drawSummary(Editor editor, MethodSite site, CcmCallRecord record,
                             int total, int index) {
        String summary = buildSummary(record, total, index);
        Runnable onClick = () -> openDetails(editor);
        CcmInlayRenderer renderer = CcmInlayRenderer.forSummary(
                summary, buildTooltip(record, total, index), new CcmGutterIcon(record, site.title), onClick);
        Inlay<?> inlay = editor.getInlayModel()
                .addAfterLineEndElement(site.lineIndex, true, renderer);
        if (inlay != null) {
            active.add(inlay);
        }
    }

    /**
     * 行尾摘要文本。
     *
     * <p>形如 {@code ▶ 412ms · id=10086, qty=1 · 1/3}。
     * 参数值在这里"收拢成一处"，不再插进形参中间，
     * 所以代码行宽度不受影响；完整 JSON 仍然在弹窗里。
     *
     * @param record 调用记录
     * @param total  总次数
     * @param index  当前下标
     * @return 一行摘要
     */
    private static String buildSummary(CcmCallRecord record, int total, int index) {
        StringBuilder sb = new StringBuilder("▶ ");
        sb.append(record.durationMs()).append("ms");
        String args = record.shortArgsInline(48);
        if (args != null && !args.isEmpty()) {
            sb.append(" · ").append(args);
        }
        if (record.exception() != null && !record.exception().isBlank()) {
            sb.append(" · ⚠ 异常");
        }
        if (total > 1) {
            sb.append(" · ").append(index + 1).append('/').append(total);
        }
        sb.append("  点击看详情");
        return sb.toString();
    }

    /** 悬停时的兜底提示（部分平台上 inlay 不显示 tooltip，因此点击才是主入口）。 */
    private static String buildTooltip(CcmCallRecord record, int total, int index) {
        return "第 " + (index + 1) + "/" + total + " 次调用，耗时 "
                + record.durationMs() + "ms（点击查看入参与返回值）";
    }

    /**
     * 给编辑器挂点击监听（每个编辑器只挂一次）。
     *
     * <h2>为什么要自己挂 MouseListener</h2>
     * {@code EditorCustomElementRenderer} 没有"点击动作"接口 ——
     * 那是 {@code GutterIconRenderer} 才有的。inlay 想响应点击，
     * 只能监听 contentComponent 的鼠标事件，再按 inlay 自己的
     * {@link Inlay#getBounds()} 做命中判断。
     *
     * <p>旧实现是<b>每加一条 inlay 就 add 一个 listener</b>，
     * 同一个文件点几次就叠几个监听，还会给每个监听都注册一次
     * EditorFactoryListener（永不注销）。这里改成每个编辑器只挂一个。
     *
     * @param editor 编辑器
     */
    private void ensureClickHandler(Editor editor) {
        if (clickHandlers.containsKey(editor)) {
            return;
        }
        MouseAdapter listener = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                handleClick(editor, e.getPoint());
            }
        };
        editor.getContentComponent().addMouseListener(listener);
        clickHandlers.put(editor, listener);
    }

    /** 命中哪条 inlay 就执行它的回调。 */
    private void handleClick(Editor editor, Point point) {
        for (Inlay<?> inlay : new ArrayList<>(active)) {
            if (inlay == null || !inlay.isValid()) {
                continue;
            }
            if (!(inlay.getRenderer() instanceof CcmInlayRenderer)) {
                continue;
            }
            Rectangle bounds = inlay.getBounds();
            if (bounds != null && bounds.contains(point)) {
                openDetails(editor);
                return;
            }
        }
    }

    /** 打开只读详情弹窗（可翻页，翻页时同步刷新行尾提示）。 */
    private void openDetails(Editor editor) {
        Context ctx = current;
        if (ctx == null) {
            return;
        }
        CcmRecordDialog dialog = new CcmRecordDialog(
                ctx.project, ctx.site.title, ctx.records, ctx.index,
                i -> showIndex(editor, i));
        dialog.show();
    }

    /**
     * 打开（或复用）编辑器。
     *
     * @param project    项目
     * @param descriptor 打开位置
     * @return 文本编辑器；打不开返回 null
     */
    private static @Nullable Editor openEditor(Project project, OpenFileDescriptor descriptor) {
        // openTextEditor 直接返回 Editor（不是 FileEditor），无需再强转
        Editor editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true);
        if (editor != null) {
            return editor;
        }
        // 兜底：某些情况下（如文件被拆分成多个编辑器）再从选中项取一次
        com.intellij.openapi.fileEditor.FileEditor selected =
                FileEditorManager.getInstance(project).getSelectedEditor(descriptor.getFile());
        if (selected instanceof TextEditor) {
            return ((TextEditor) selected).getEditor();
        }
        return null;
    }

    /**
     * 清理编辑器上本类添加过的 inlay。
     *
     * @param editor 编辑器
     */
    private void clearInlays(Editor editor) {
        for (Inlay<?> inlay : new ArrayList<>(active)) {
            if (inlay != null && inlay.isValid()) {
                inlay.dispose();
            }
        }
        active.clear();
    }

    /** 方法在源码中的位置信息。 */
    private static final class MethodSite {
        final VirtualFile file;
        final int methodOffset;
        final int lineIndex;
        /** 「类#方法」，用于弹窗标题。 */
        final String title;

        MethodSite(VirtualFile file, int methodOffset, int lineIndex, String title) {
            this.file = file;
            this.methodOffset = methodOffset;
            this.lineIndex = lineIndex;
            this.title = title;
        }
    }

    /**
     * 当前展示上下文：翻页 / 打开详情时不必重新读文件、重新做 PSI 定位。
     */
    private static final class Context {
        final Project project;
        final MethodSite site;
        final List<CcmCallRecord> records;
        int index;

        Context(Project project, MethodSite site, List<CcmCallRecord> records, int index) {
            this.project = project;
            this.site = site;
            this.records = Collections.unmodifiableList(records);
            this.index = index;
        }
    }
}
