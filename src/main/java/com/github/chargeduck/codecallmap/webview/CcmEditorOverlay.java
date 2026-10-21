package com.github.chargeduck.codecallmap.webview;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把录制数据绘制到编辑器里的覆盖层。
 *
 * <h2>职责边界</h2>
 * 只做「找到方法 → 算偏移 → 加 inlay → 记住句柄以便清理」，不做数据读取
 * （那是 {@link CcmCallRecordReader}），也不做展示决策（那是 {@link CcmInlayRenderer}）。
 *
 * <h2>为什么每次都要先清空</h2>
 * 用户可能连续点时序图里不同的箭头，每次都是同一个编辑器。
 * 如果不在每次绘制前清掉上一批 inlay，灰字就会层层叠加，代码被挤得没法看。
 *
 * <h2>为什么全部在 EDT 上做</h2>
 * {@code InlayModel} 是编辑器模型的一部分，只能在写安全的上下文里改。
 * PSI 查询需要读锁，所以拆成两段：ReadAction 里取 PSI 信息，
 * EDT 里只做纯 UI 操作，避免在读锁里做 UI（会触发死锁检测）。
 *
 * @author chargeduck
 */
public final class CcmEditorOverlay {

    /** 当前已添加的 inlay，用于切换方法时清理。 */
    private final List<Inlay<?>> active = new ArrayList<>();

    /**
     * 当前展示的上下文，供「点击切换下一条调用」时重绘使用。
     *
     * <p>切换只是把 {@code index} 改成下一条然后重画，不需要重新读文件、
     * 也不需要重新做 PSI 定位，所以把这两样都缓存下来。
     */
    private Context current;

    /**
     * 在编辑器里展示某方法的某一次调用。
     *
     * @param project   项目
     * @param className 全限定类名
     * @param methodName 方法名
     * @param records   该方法的所有调用记录（按时间升序）
     * @param index     展示第几次（0-based，越界自动收敛）
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

        // 第一段：读锁里取 PSI 信息（类、方法、参数名与偏移、文件）
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
            // 记住上下文：点「1/3」切换下一条时靠它重绘
            current = new Context(project, site, records, safeIndex);
            drawInlays(editor, site, record, records.size(), safeIndex);
            // 滚动到方法处，让用户立刻看到
            editor.getScrollingModel().scrollTo(
                    new LogicalPosition(editor.getDocument().getLineNumber(site.methodOffset), 0),
                    ScrollType.CENTER);
        });
        return true;
    }

    /**
     * 切到同一方法的下一条调用（循环）。
     *
     * <p>由行尾摘要 inlay 的点击回调触发。用户希望"同一方法被调了 5 次，
     * 点一下看下一次"，这比重新在时序图里找箭头快得多。
     *
     * @param editor 当前编辑器
     */
    public void showNext(Editor editor) {
        Context ctx = current;
        if (ctx == null || editor == null || ctx.records.size() <= 1) {
            return;
        }
        int next = (ctx.index + 1) % ctx.records.size();
        ctx.index = next;
        CcmCallRecord record = ctx.records.get(next);
        clearInlays(editor);
        drawInlays(editor, ctx.site, record, ctx.records.size(), next);
    }

    /**
     * 清理当前文件里的录制 inlay。
     * <p>
     * 由调用方在"关闭录制视图"时触发；切换方法时会自动先清。
     */
    public void clear() {
        ApplicationManager.getApplication().invokeLater(() -> {
            for (Inlay<?> inlay : new ArrayList<>(active)) {
                if (inlay != null && inlay.isValid()) {
                    inlay.dispose();
                }
            }
            active.clear();
        });
    }

    // ------------------------------------------------------------------
    // PSI 定位
    // ------------------------------------------------------------------

    /**
     * 定位方法在源码中的位置与参数信息。
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

        // 参数名 -> 参数结束偏移（inlay 就插在参数后面）
        List<ParamSite> params = new ArrayList<>();
        for (PsiParameter p : target.getParameterList().getParameters()) {
            if (p.getTextRange() == null) {
                continue;
            }
            params.add(new ParamSite(p.getName(), p.getTextRange().getEndOffset()));
        }
        return new MethodSite(psiFile.getVirtualFile(), methodOffset, lineIndex, params);
    }

    // ------------------------------------------------------------------
    // inlay 绘制
    // ------------------------------------------------------------------

    /**
     * 在编辑器上绘制三类 inlay。
     *
     * @param editor     编辑器
     * @param site       方法位置信息
     * @param record     本次要展示的调用记录
     * @param total      该方法总调用次数（用于 tooltip 说明"第几次"）
     * @param index      当前展示第几次（0-based）
     */
    private void drawInlays(Editor editor, MethodSite site, CcmCallRecord record,
                            int total, int index) {
        // ---- A：参数后显示实参值 ----
        for (ParamSite p : site.params) {
            if (p.name == null) {
                continue;
            }
            String value = record.shortArg(p.name);
            if (value == null) {
                continue;
            }
            Inlay<?> inlay = editor.getInlayModel()
                    .addInlineElement(p.endOffset, true, CcmInlayRenderer.forArg(p.name, value));
            if (inlay != null) {
                active.add(inlay);
            }
        }

        // ---- B：行尾汇总 + gutter 图标 ----
        StringBuilder tip = new StringBuilder();
        tip.append("第 ").append(index + 1).append('/').append(total).append(" 次调用\n");
        tip.append("耗时 ").append(record.durationMs()).append("ms\n");
        tip.append("\n── 入参 ──\n").append(record.fullArgsText());
        tip.append("\n── 返回值 ──\n").append(record.fullRetText());
        if (record.exception() != null) {
            tip.append("\n── 异常 ──\n").append(record.exception());
        }

        boolean multi = total > 1;
        String summaryText = "▶ " + record.shortSummary()
                + (multi ? " · " + (index + 1) + "/" + total + "（点击切换）" : "");
        // 多次调用时让行尾摘要可点击：点一下切到下一条。
        // 交接文档里这条是"只做了一半"的缺口，这里补完。
        Runnable onClick = multi ? () -> showNext(editor) : null;
        CcmInlayRenderer summary = CcmInlayRenderer.forSummaryClickable(
                summaryText, tip.toString(), new CcmGutterIcon(record), onClick);
        Inlay<?> summaryInlay = editor.getInlayModel()
                .addAfterLineEndElement(site.lineIndex, true, summary);
        if (summaryInlay != null) {
            active.add(summaryInlay);
            if (onClick != null) {
                installClickHandler(editor, summaryInlay, onClick);
            }
        }
    }

    /**
     * 给 inlay 挂鼠标点击监听。
     *
     * <h2>为什么必须自己挂 MouseListener</h2>
     * {@code EditorCustomElementRenderer} 没有点击接口。inlay 是画在编辑器
     * 文本区里的图形，要响应点击只能监听 contentComponent 的鼠标事件，
     * 再按 inlay 自己的 {@link Inlay#getBounds()} 做命中判断。
     *
     * <p>监听器随 inlay 一起失效：inlay 被 dispose 后 {@code isValid()} 为 false，
     * 监听里直接忽略，不需要额外注销（编辑器销毁时 contentComponent 也会回收）。
     *
     * @param editor  编辑器
     * @param inlay   目标 inlay
     * @param onClick 命中后执行
     */
    private static void installClickHandler(Editor editor, Inlay<?> inlay, Runnable onClick) {
        java.awt.event.MouseAdapter listener = new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (!inlay.isValid()) {
                    return;
                }
                Rectangle bounds = inlay.getBounds();
                if (bounds != null && bounds.contains(e.getPoint())) {
                    onClick.run();
                }
            }
        };
        editor.getContentComponent().addMouseListener(listener);
        // 编辑器释放时移除监听，避免长期打开多个文件后监听器越积越多。
        // 用 EditorFactory 的编辑器释放事件而不是 Disposer ——
        // Editor 接口本身没有 getDisposable()，它挂在文件编辑器下面。
        com.intellij.openapi.editor.EditorFactory.getInstance()
                .addEditorFactoryListener(new com.intellij.openapi.editor.event.EditorFactoryListener() {
                    @Override
                    public void editorReleased(@NotNull com.intellij.openapi.editor.event.EditorFactoryEvent event) {
                        if (event.getEditor() == editor) {
                            editor.getContentComponent().removeMouseListener(listener);
                        }
                    }
                }, editor.getProject());
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
        final List<ParamSite> params;

        MethodSite(VirtualFile file, int methodOffset, int lineIndex, List<ParamSite> params) {
            this.file = file;
            this.methodOffset = methodOffset;
            this.lineIndex = lineIndex;
            this.params = params;
        }
    }

    /** 参数名与结束偏移。 */
    private static final class ParamSite {
        final String name;
        final int endOffset;

        ParamSite(String name, int endOffset) {
            this.name = name;
            this.endOffset = endOffset;
        }
    }

    /**
     * 当前展示上下文：切换"下一条调用"时不必重新读文件、重新做 PSI 定位。
     */
    private static final class Context {
        final Project project;
        final MethodSite site;
        final List<CcmCallRecord> records;
        int index;

        Context(Project project, MethodSite site, List<CcmCallRecord> records, int index) {
            this.project = project;
            this.site = site;
            this.records = records;
            this.index = index;
        }
    }
}
