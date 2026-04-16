package com.ubuntu4m.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.OverScroller;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class TerminalView extends View {

    private static final int TERM_COLUMNS = 80;
    private static final int TERM_ROWS = 24;
    private static final int MAX_SCROLLBACK = 5000;
    private static final int COLOR_BACKGROUND = 0xFF0B0F10;
    private static final int COLOR_TEXT = 0xFFE6EDF3;
    private static final int COLOR_CURSOR = 0xFF5FE68A;
    private static final int COLOR_OVERLAY = 0xDD161C23;
    private static final int COLOR_OVERLAY_TEXT = 0xFF8B949E;
    private static final int COLOR_PREVIEW = 0xFFBCEFCB;
    private static final int COLOR_SELECTION = 0x6638A169;
    private static final int COLOR_SELECTION_HANDLE = 0xFF5FE68A;
    private static final int COLOR_SELECTION_BUTTON = 0xEE11161C;
    private static final int COLOR_SELECTION_BUTTON_TEXT = 0xFFE6EDF3;
    private static final int[] ANSI_PALETTE = new int[]{
            0xFF20262D, 0xFFFF7B72, 0xFF3FB950, 0xFFD29922,
            0xFF79C0FF, 0xFFD2A8FF, 0xFF39C5CF, 0xFFE6EDF3,
            0xFF6E7681, 0xFFFFA198, 0xFF56D364, 0xFFE3B341,
            0xFFA5D6FF, 0xFFE2C5FF, 0xFF76E3EA, 0xFFF0F6FC
    };
    private static final float MIN_FONT_SP = 11f;
    private static final float DEFAULT_FONT_SP = MIN_FONT_SP;
    private static final float MAX_FONT_SP = 24f;

    private Terminal terminal;
    private final Paint textPaint;
    private final Paint previewPaint;
    private final Paint bgPaint;
    private final Paint cursorPaint;
    private final Paint overlayPaint;
    private final Paint overlayTextPaint;
    private final Paint selectionPaint;
    private final Paint selectionHandlePaint;
    private final Paint selectionButtonPaint;
    private final Paint selectionButtonTextPaint;
    private final GestureDetector gestureDetector;
    private final ScaleGestureDetector scaleGestureDetector;
    private final OverScroller flingScroller;
    private final Runnable flingRunner;
    private final TerminalEmulator emulator;
    private final int contentInsetX;
    private final int contentInsetY;
    private final int cursorThickness;
    private final int overlayPadding;
    private final RectF overlayRect = new RectF();
    private final RectF selectionStartHandleRect = new RectF();
    private final RectF selectionEndHandleRect = new RectF();
    private final RectF selectionButtonRect = new RectF();
    private float charWidth;
    private float charHeight;
    private float baselineOffset;
    private int visibleRows;
    private int scrollOffset = 0;
    private float scrollAccumulator;
    private int lastFlingY;
    private float fontSizeSp = DEFAULT_FONT_SP;
    private boolean scaleActive;
    private SelectionAnchor selectionStart;
    private SelectionAnchor selectionEnd;
    private SelectionTouchTarget selectionTouchTarget = SelectionTouchTarget.NONE;
    private boolean selectionButtonPressed;
    private final float selectionHandleRadius;
    private final float selectionHandleStemHeight;
    private final float selectionButtonPaddingH;
    private final float selectionButtonPaddingV;
    private final float selectionButtonGap;

    private final StringBuilder composingInput = new StringBuilder();

    public TerminalView(Context context) {
        super(context);

        textPaint = new Paint();
        textPaint.setColor(COLOR_TEXT);
        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setAntiAlias(true);
        textPaint.setSubpixelText(true);

        previewPaint = new Paint();
        previewPaint.setColor(COLOR_PREVIEW);
        previewPaint.setTypeface(Typeface.MONOSPACE);
        previewPaint.setAntiAlias(true);
        previewPaint.setSubpixelText(true);

        bgPaint = new Paint();
        bgPaint.setColor(COLOR_BACKGROUND);
        bgPaint.setStyle(Paint.Style.FILL);

        cursorPaint = new Paint();
        cursorPaint.setColor(COLOR_CURSOR);
        cursorPaint.setStyle(Paint.Style.FILL);

        overlayPaint = new Paint();
        overlayPaint.setColor(COLOR_OVERLAY);
        overlayPaint.setStyle(Paint.Style.FILL);

        overlayTextPaint = new Paint();
        overlayTextPaint.setColor(COLOR_OVERLAY_TEXT);
        overlayTextPaint.setTypeface(Typeface.MONOSPACE);
        overlayTextPaint.setTextSize(sp(11f));
        overlayTextPaint.setAntiAlias(true);

        selectionPaint = new Paint();
        selectionPaint.setColor(COLOR_SELECTION);
        selectionPaint.setStyle(Paint.Style.FILL);

        selectionHandlePaint = new Paint();
        selectionHandlePaint.setColor(COLOR_SELECTION_HANDLE);
        selectionHandlePaint.setStyle(Paint.Style.FILL);
        selectionHandlePaint.setAntiAlias(true);

        selectionButtonPaint = new Paint();
        selectionButtonPaint.setColor(COLOR_SELECTION_BUTTON);
        selectionButtonPaint.setStyle(Paint.Style.FILL);
        selectionButtonPaint.setAntiAlias(true);

        selectionButtonTextPaint = new Paint();
        selectionButtonTextPaint.setColor(COLOR_SELECTION_BUTTON_TEXT);
        selectionButtonTextPaint.setTypeface(Typeface.MONOSPACE);
        selectionButtonTextPaint.setTextSize(sp(12f));
        selectionButtonTextPaint.setAntiAlias(true);

        contentInsetX = dp(14);
        contentInsetY = dp(12);
        cursorThickness = dp(2);
        overlayPadding = dp(8);
        selectionHandleRadius = dp(7);
        selectionHandleStemHeight = dp(16);
        selectionButtonPaddingH = dp(12);
        selectionButtonPaddingV = dp(8);
        selectionButtonGap = dp(10);

        emulator = new TerminalEmulator(TERM_ROWS, TERM_COLUMNS, MAX_SCROLLBACK);
        gestureDetector = new GestureDetector(context, new TerminalGestureListener());
        scaleGestureDetector = new ScaleGestureDetector(context, new TerminalScaleListener());
        flingScroller = new OverScroller(context);
        flingRunner = new Runnable() {
            @Override
            public void run() {
                if (!flingScroller.computeScrollOffset()) {
                    return;
                }

                int currentY = flingScroller.getCurrY();
                int deltaY = currentY - lastFlingY;
                lastFlingY = currentY;
                scrollByPixels(deltaY);

                if (shouldStopFling(deltaY)) {
                    stopFling();
                    return;
                }
                postOnAnimation(this);
            }
        };

        updateTextMetrics();

        setPadding(contentInsetX, contentInsetY, contentInsetX, contentInsetY);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        setLongClickable(true);
    }

    public void setTerminal(Terminal terminal) {
        this.terminal = terminal;
    }

    public String getAllText() {
        return emulator.getAllText();
    }

    public boolean isAlternateScreenActive() {
        return emulator.isAlternateScreenActive();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (handleSelectionTouchEvent(event)) {
            return true;
        }

        boolean handled = scaleGestureDetector.onTouchEvent(event);
        if (!scaleGestureDetector.isInProgress()) {
            handled = gestureDetector.onTouchEvent(event) || handled;
        }

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            stopFling();
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            scaleActive = false;
            if (action == MotionEvent.ACTION_UP) {
                performClick();
            }
        }
        return handled || super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    public void copyAllToClipboard() {
        copyTextToClipboard(getAllText(), "terminal output", "Copied session output");
    }

    private void copySelectionToClipboard() {
        String selectedText = getSelectedText();
        if (selectedText.length() == 0) {
            toast("当前没有可复制内容");
            return;
        }
        copyTextToClipboard(selectedText, "terminal selection", "已复制");
    }

    private void copyTextToClipboard(String text, String label, String toastMessage) {
        ClipboardManager clipboard = (ClipboardManager) getContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || text == null || text.length() == 0) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
        toast(toastMessage);
    }

    public void pasteFromClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            toast("Clipboard is empty");
            return;
        }

        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            toast("Clipboard is empty");
            return;
        }

        CharSequence text = clip.getItemAt(0).coerceToText(getContext());
        if (text == null || text.length() == 0) {
            toast("Clipboard is empty");
            return;
        }
        commitInputText(text.toString());
    }

    public void sendInput(String text) {
        commitInputText(text);
    }

    public void sendControlKey(char key) {
        char lower = Character.toLowerCase(key);
        if (lower >= 'a' && lower <= 'z') {
            commitInputText(String.valueOf((char) (lower - 'a' + 1)));
        }
    }

    public void pageUp() {
        if (emulator.isAlternateScreenActive()) {
            commitInputText("\033[5~");
            return;
        }
        scrollByRows(Math.max(1, visibleRows - 2));
    }

    public void pageDown() {
        if (emulator.isAlternateScreenActive()) {
            commitInputText("\033[6~");
            return;
        }
        scrollByRows(-Math.max(1, visibleRows - 2));
    }

    public void scrollToBottom() {
        if (emulator.isAlternateScreenActive()) {
            return;
        }
        scrollOffset = 0;
        updateSelectionUi();
        invalidate();
    }

    public void showKeyboard() {
        showSoftInput();
    }

    public void setComposingText(CharSequence text) {
        composingInput.setLength(0);
        if (!emulator.isAlternateScreenActive() && text != null) {
            composingInput.append(text);
        }
        invalidate();
    }

    public void clearComposingText() {
        if (composingInput.length() == 0) {
            return;
        }
        composingInput.setLength(0);
        invalidate();
    }

    public void deleteBeforeCursor(int count) {
        if (count <= 0) {
            return;
        }

        while (count > 0 && composingInput.length() > 0) {
            composingInput.deleteCharAt(composingInput.length() - 1);
            count--;
        }

        if (count > 0) {
            commitInputText(repeatBackspace(count));
        } else {
            invalidate();
        }
    }

    public void appendOutput(String text) {
        if (text == null || text.length() == 0) {
            return;
        }

        boolean stickToBottom = scrollOffset == 0 || emulator.isAlternateScreenActive();
        clearComposingText();
        clearSelection();
        emulator.append(text);

        if (stickToBottom) {
            scrollOffset = 0;
        } else {
            clampScrollOffset();
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        recalculateViewport();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        int left = getPaddingLeft();
        int right = width - getPaddingRight();
        int top = getPaddingTop();

        bgPaint.setColor(COLOR_BACKGROUND);
        canvas.drawRect(0, 0, width, height, bgPaint);

        int totalRows = getTotalDisplayRows();
        int startRow = getViewportStartRow(totalRows);
        int endRow = Math.min(totalRows, startRow + visibleRows);

        float baselineToTop = baselineOffset - top;
        float y = baselineOffset;
        for (int rowIndex = startRow; rowIndex < endRow; rowIndex++) {
            char[] row = getDisplayRow(rowIndex);
            int[] fgRow = getDisplayForegroundRow(rowIndex);
            int[] bgRow = getDisplayBackgroundRow(rowIndex);
            int rowLength = findLastVisibleCell(row, bgRow);
            drawSelectionHighlight(canvas, rowIndex, startRow, left, top);
            if (rowLength > 0) {
                drawStyledRow(canvas, row, fgRow, bgRow, rowLength, left, y, baselineToTop);
            }
            y += charHeight;
        }

        if (!emulator.isAlternateScreenActive() && composingInput.length() > 0) {
            drawComposingText(canvas, startRow, endRow, left);
        }

        if (!hasSelection() && scrollOffset == 0 && emulator.isCursorVisible()) {
            CursorPosition cursor = getCursorPosition();
            if (cursor.rowIndex >= startRow && cursor.rowIndex < endRow) {
                drawCursor(canvas, cursor, startRow, left, top);
            }
        }

        drawSelectionControls(canvas, startRow, endRow, left, right, top,
                height - getPaddingBottom());

        if (!emulator.isAlternateScreenActive() && scrollOffset > 0) {
            String indicator = "history +" + scrollOffset;
            float textWidth = overlayTextPaint.measureText(indicator);
            float overlayWidth = textWidth + overlayPadding * 2f;
            float overlayHeight = overlayTextPaint.getTextSize() + overlayPadding * 1.5f;
            float overlayLeft = right - overlayWidth;
            float overlayTop = top;
            overlayRect.set(overlayLeft, overlayTop,
                    overlayLeft + overlayWidth, overlayTop + overlayHeight);
            canvas.drawRoundRect(overlayRect, dp(8), dp(8), overlayPaint);
            canvas.drawText(indicator, overlayLeft + overlayPadding,
                    overlayTop + overlayHeight - overlayPadding, overlayTextPaint);
        }
    }

    private void drawComposingText(Canvas canvas, int startRow, int endRow, int left) {
        int baseRow = emulator.getCursorRowIndexForDisplay();
        int baseCol = emulator.getCursorColumn();
        int previewLength = composingInput.length();
        int previewIndex = 0;

        while (previewIndex < previewLength) {
            int absoluteCol = baseCol + previewIndex;
            int rowOffset = absoluteCol / TERM_COLUMNS;
            int col = absoluteCol % TERM_COLUMNS;
            int rowIndex = baseRow + rowOffset;
            if (rowIndex >= endRow) {
                break;
            }

            int segmentLength = Math.min(previewLength - previewIndex, TERM_COLUMNS - col);
            if (rowIndex >= startRow) {
                float x = left + col * charWidth;
                float y = baselineOffset + (rowIndex - startRow) * charHeight;
                canvas.drawText(composingInput, previewIndex, previewIndex + segmentLength,
                        x, y, previewPaint);
            }
            previewIndex += segmentLength;
        }
    }

    private void drawStyledRow(Canvas canvas, char[] row, int[] fgRow, int[] bgRow,
                               int rowLength, int left, float baselineY, float baselineToTop) {
        int segmentStart = 0;
        while (segmentStart < rowLength) {
            int fg = fgRow[segmentStart];
            int bg = bgRow[segmentStart];
            int segmentEnd = segmentStart + 1;
            while (segmentEnd < rowLength
                    && fgRow[segmentEnd] == fg
                    && bgRow[segmentEnd] == bg) {
                segmentEnd++;
            }

            float segmentLeft = left + segmentStart * charWidth;
            float segmentRight = left + segmentEnd * charWidth;
            float rowTop = baselineY - baselineToTop;
            if (bg != COLOR_BACKGROUND) {
                bgPaint.setColor(bg);
                canvas.drawRect(segmentLeft, rowTop, segmentRight, rowTop + charHeight, bgPaint);
            }

            if (segmentHasVisibleText(row, segmentStart, segmentEnd)) {
                textPaint.setColor(fg);
                canvas.drawText(row, segmentStart, segmentEnd - segmentStart,
                        segmentLeft, baselineY, textPaint);
            }
            segmentStart = segmentEnd;
        }
    }

    private void drawSelectionHighlight(Canvas canvas, int rowIndex, int startRow, int left, int top) {
        SelectionSegment segment = getSelectionSegment(rowIndex);
        if (segment == null) {
            return;
        }

        float rowTop = top + (rowIndex - startRow) * charHeight;
        float segmentLeft = getBoundaryX(rowIndex, segment.startColumn, left);
        float segmentRight = getBoundaryX(rowIndex, segment.endColumn, left);
        if (segmentRight <= segmentLeft) {
            return;
        }
        canvas.drawRect(segmentLeft, rowTop, segmentRight, rowTop + charHeight, selectionPaint);
    }

    private void drawSelectionControls(Canvas canvas, int startRow, int endRow,
                                       int left, int right, int top, int bottom) {
        if (!hasSelection()) {
            resetSelectionUiRects();
            return;
        }

        SelectionAnchor orderedStart = getOrderedSelectionStart();
        SelectionAnchor orderedEnd = getOrderedSelectionEnd();
        updateSelectionUi(startRow, endRow, left, right, top, bottom);

        if (orderedStart.rowIndex >= startRow && orderedStart.rowIndex < endRow) {
            drawSelectionHandle(canvas, orderedStart, startRow, left, selectionStartHandleRect);
        }
        if (orderedEnd.rowIndex >= startRow && orderedEnd.rowIndex < endRow) {
            drawSelectionHandle(canvas, orderedEnd, startRow, left, selectionEndHandleRect);
        }
        if (!selectionButtonRect.isEmpty()) {
            int buttonColor = selectionButtonPressed
                    ? 0xFF1A2430
                    : COLOR_SELECTION_BUTTON;
            selectionButtonPaint.setColor(buttonColor);
            float radius = dp(10);
            canvas.drawRoundRect(selectionButtonRect, radius, radius, selectionButtonPaint);

            float textWidth = selectionButtonTextPaint.measureText("复制");
            Paint.FontMetrics metrics = selectionButtonTextPaint.getFontMetrics();
            float textX = selectionButtonRect.centerX() - textWidth / 2f;
            float textY = selectionButtonRect.centerY() - (metrics.ascent + metrics.descent) / 2f;
            canvas.drawText("复制", textX, textY, selectionButtonTextPaint);
        }
    }

    private void drawSelectionHandle(Canvas canvas, SelectionAnchor anchor, int startRow, int left,
                                     RectF outBounds) {
        float x = getBoundaryX(anchor.rowIndex, anchor.column, left);
        float lineTop = getPaddingTop() + (anchor.rowIndex - startRow + 1) * charHeight - dp(1);
        float circleCenterY = lineTop + selectionHandleStemHeight;

        canvas.drawRect(x - dp(1), lineTop, x + dp(1), circleCenterY, selectionHandlePaint);
        canvas.drawCircle(x, circleCenterY, selectionHandleRadius, selectionHandlePaint);

        outBounds.set(
                x - selectionHandleRadius * 1.8f,
                lineTop - selectionHandleRadius,
                x + selectionHandleRadius * 1.8f,
                circleCenterY + selectionHandleRadius
        );
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (terminal == null) {
            return super.onKeyDown(keyCode, event);
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
                commitInputText("\r");
                return true;
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_FORWARD_DEL:
                deleteBeforeCursor(1);
                return true;
            case KeyEvent.KEYCODE_TAB:
                commitInputText("\t");
                return true;
            case KeyEvent.KEYCODE_ESCAPE:
                commitInputText("\033");
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                commitInputText("\033[A");
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                commitInputText("\033[B");
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                commitInputText("\033[D");
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                commitInputText("\033[C");
                return true;
            case KeyEvent.KEYCODE_PAGE_UP:
                pageUp();
                return true;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                pageDown();
                return true;
        }

        int unicodeChar = event.getUnicodeChar();
        if (unicodeChar != 0) {
            commitInputText(String.valueOf((char) unicodeChar));
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
        outAttrs.initialSelStart = 0;
        outAttrs.initialSelEnd = 0;
        return new TerminalInputConnection(this, terminal);
    }

    private void showSoftInput() {
        requestFocus();
        InputMethodManager imm = (InputMethodManager) getContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(this, 0);
        }
    }

    private void scrollByGesture(float distanceY) {
        if (emulator.isAlternateScreenActive()) {
            return;
        }
        scrollByPixels(applyGestureAcceleration(distanceY));
    }

    private void scrollByPixels(float deltaY) {
        if (emulator.isAlternateScreenActive()) {
            return;
        }

        scrollAccumulator += deltaY;
        while (scrollAccumulator >= charHeight) {
            scrollOffset++;
            scrollAccumulator -= charHeight;
        }
        while (scrollAccumulator <= -charHeight) {
            scrollOffset--;
            scrollAccumulator += charHeight;
        }
        clampScrollOffset();
        trimScrollAccumulatorAtBoundary();
        updateSelectionUi();
        invalidate();
    }

    private float applyGestureAcceleration(float distanceY) {
        float speed = Math.abs(distanceY);
        float factor = 1f + Math.min(2.5f, speed / Math.max(1f, charHeight * 1.2f));
        return distanceY * factor;
    }

    private void scrollByRows(int rows) {
        if (rows == 0 || emulator.isAlternateScreenActive()) {
            return;
        }
        scrollOffset += rows;
        clampScrollOffset();
        updateSelectionUi();
        invalidate();
    }

    private void clampScrollOffset() {
        int maxScroll = Math.max(0, getTotalDisplayRows() - visibleRows);
        if (scrollOffset < 0) {
            scrollOffset = 0;
        } else if (scrollOffset > maxScroll) {
            scrollOffset = maxScroll;
        }
    }

    private void trimScrollAccumulatorAtBoundary() {
        int maxScroll = Math.max(0, getTotalDisplayRows() - visibleRows);
        if ((scrollOffset == 0 && scrollAccumulator < 0f)
                || (scrollOffset == maxScroll && scrollAccumulator > 0f)) {
            scrollAccumulator = 0f;
        }
    }

    private boolean shouldStopFling(float deltaY) {
        if (emulator.isAlternateScreenActive() || flingScroller.isFinished()) {
            return true;
        }

        int maxScroll = Math.max(0, getTotalDisplayRows() - visibleRows);
        return (scrollOffset == 0 && deltaY < 0f)
                || (scrollOffset == maxScroll && deltaY > 0f);
    }

    private void startFling(float velocityY) {
        stopFling();
        if (emulator.isAlternateScreenActive() || getTotalDisplayRows() <= visibleRows) {
            return;
        }

        int maxScroll = Math.max(1, Math.round((getTotalDisplayRows() - visibleRows) * charHeight));
        lastFlingY = 0;
        flingScroller.fling(0, 0, 0, Math.round(velocityY), 0, 0, -maxScroll, maxScroll);
        postOnAnimation(flingRunner);
    }

    private void stopFling() {
        if (!flingScroller.isFinished()) {
            flingScroller.abortAnimation();
        }
        removeCallbacks(flingRunner);
        lastFlingY = 0;
    }

    private void updateTextMetrics() {
        float textSize = sp(fontSizeSp);
        textPaint.setTextSize(textSize);
        previewPaint.setTextSize(textSize);

        Paint.FontMetrics fontMetrics = textPaint.getFontMetrics();
        charWidth = textPaint.measureText("M");
        charHeight = (fontMetrics.descent - fontMetrics.ascent) * 1.18f;
        baselineOffset = contentInsetY - fontMetrics.ascent;

        recalculateViewport();
        updateSelectionUi();
        invalidate();
    }

    private void recalculateViewport() {
        int contentHeight = Math.max(1, getHeight() - getPaddingTop() - getPaddingBottom());
        visibleRows = Math.max(1, (int) (contentHeight / charHeight));
        clampScrollOffset();
        updateSelectionUi();
    }

    private void commitInputText(String text) {
        if (terminal == null || text == null || text.length() == 0) {
            return;
        }

        clearSelection();
        clearComposingText();
        terminal.writeInput(text);
        if (!emulator.isAlternateScreenActive()) {
            scrollToBottom();
        }
    }

    private int getTotalDisplayRows() {
        int totalRows = emulator.getDisplayRowCount();
        if (!emulator.isAlternateScreenActive() && composingInput.length() > 0) {
            int extraRows = (emulator.getCursorColumn() + composingInput.length()) / TERM_COLUMNS;
            totalRows += extraRows;
        }
        return totalRows;
    }

    private int getAlternateScreenStartRow(int totalRows) {
        if (visibleRows >= totalRows) {
            return 0;
        }

        int preferred = emulator.getCursorRowIndexForDisplay() - visibleRows + 1;
        int maxStart = Math.max(0, totalRows - visibleRows);
        if (preferred < 0) {
            return 0;
        }
        if (preferred > maxStart) {
            return maxStart;
        }
        return preferred;
    }

    private int getViewportStartRow(int totalRows) {
        return emulator.isAlternateScreenActive()
                ? getAlternateScreenStartRow(totalRows)
                : Math.max(0, totalRows - visibleRows - scrollOffset);
    }

    private boolean handleSelectionTouchEvent(MotionEvent event) {
        if (!hasSelection() && selectionTouchTarget == SelectionTouchTarget.NONE) {
            return false;
        }

        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                updateSelectionUi();
                stopFling();
                selectionTouchTarget = hitTestSelectionTarget(x, y);
                if (selectionTouchTarget == SelectionTouchTarget.NONE) {
                    clearSelection();
                    return false;
                }
                selectionButtonPressed = selectionTouchTarget == SelectionTouchTarget.COPY_BUTTON;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (selectionTouchTarget == SelectionTouchTarget.START_HANDLE) {
                    updateSelectionHandlePosition(x, y, true);
                    return true;
                }
                if (selectionTouchTarget == SelectionTouchTarget.END_HANDLE) {
                    updateSelectionHandlePosition(x, y, false);
                    return true;
                }
                if (selectionTouchTarget == SelectionTouchTarget.COPY_BUTTON) {
                    boolean nextPressed = selectionButtonRect.contains(x, y);
                    if (nextPressed != selectionButtonPressed) {
                        selectionButtonPressed = nextPressed;
                        invalidate();
                    }
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
                if (selectionTouchTarget == SelectionTouchTarget.COPY_BUTTON) {
                    boolean shouldCopy = selectionButtonRect.contains(x, y);
                    selectionTouchTarget = SelectionTouchTarget.NONE;
                    selectionButtonPressed = false;
                    invalidate();
                    if (shouldCopy) {
                        copySelectionToClipboard();
                    }
                    return true;
                }
                if (selectionTouchTarget == SelectionTouchTarget.START_HANDLE
                        || selectionTouchTarget == SelectionTouchTarget.END_HANDLE) {
                    selectionTouchTarget = SelectionTouchTarget.NONE;
                    invalidate();
                    return true;
                }
                return false;
            case MotionEvent.ACTION_CANCEL:
                if (selectionTouchTarget != SelectionTouchTarget.NONE || selectionButtonPressed) {
                    selectionTouchTarget = SelectionTouchTarget.NONE;
                    selectionButtonPressed = false;
                    invalidate();
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    private void beginLineSelection(MotionEvent event) {
        int rowIndex = resolveDisplayRowIndex(event.getY());
        if (rowIndex < 0) {
            return;
        }

        int startColumn = getRowTextStart(rowIndex);
        int endColumn = getRowTextEnd(rowIndex);
        if (endColumn <= startColumn) {
            clearSelection();
            toast("当前行没有可复制内容");
            return;
        }

        selectionStart = new SelectionAnchor(rowIndex, startColumn);
        selectionEnd = new SelectionAnchor(rowIndex, endColumn);
        selectionTouchTarget = SelectionTouchTarget.NONE;
        selectionButtonPressed = false;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        updateSelectionUi();
        invalidate();
    }

    private void updateSelectionHandlePosition(float x, float y, boolean adjustingStart) {
        int rowIndex = resolveDisplayRowIndex(y);
        if (rowIndex < 0) {
            return;
        }

        SelectionAnchor current = adjustingStart ? selectionEnd : selectionStart;
        int left = getPaddingLeft();
        int column = resolveColumnIndex(rowIndex, x, left);
        if (compareSelectionAnchors(new SelectionAnchor(rowIndex, column), current) == 0) {
            if (adjustingStart) {
                column = column > 0 ? column - 1 : Math.min(TERM_COLUMNS, column + 1);
            } else {
                column = Math.min(TERM_COLUMNS, column + 1);
            }
        }

        if (adjustingStart) {
            selectionStart = new SelectionAnchor(rowIndex, column);
            if (compareSelectionAnchors(selectionStart, selectionEnd) > 0) {
                SelectionAnchor previousEnd = selectionEnd;
                selectionEnd = selectionStart;
                selectionStart = previousEnd;
                selectionTouchTarget = SelectionTouchTarget.END_HANDLE;
            }
        } else {
            selectionEnd = new SelectionAnchor(rowIndex, column);
            if (compareSelectionAnchors(selectionStart, selectionEnd) > 0) {
                SelectionAnchor previousStart = selectionStart;
                selectionStart = selectionEnd;
                selectionEnd = previousStart;
                selectionTouchTarget = SelectionTouchTarget.START_HANDLE;
            }
        }
        updateSelectionUi();
        invalidate();
    }

    private void clearSelection() {
        if (!hasSelection()
                && selectionTouchTarget == SelectionTouchTarget.NONE
                && !selectionButtonPressed) {
            return;
        }

        selectionStart = null;
        selectionEnd = null;
        selectionTouchTarget = SelectionTouchTarget.NONE;
        selectionButtonPressed = false;
        resetSelectionUiRects();
        invalidate();
    }

    private boolean hasSelection() {
        return selectionStart != null
                && selectionEnd != null
                && compareSelectionAnchors(selectionStart, selectionEnd) != 0;
    }

    private void updateSelectionUi() {
        updateSelectionUi(getViewportStartRow(getTotalDisplayRows()),
                Math.min(getTotalDisplayRows(),
                        getViewportStartRow(getTotalDisplayRows()) + visibleRows),
                getPaddingLeft(),
                getWidth() - getPaddingRight(),
                getPaddingTop(),
                getHeight() - getPaddingBottom());
    }

    private void updateSelectionUi(int startRow, int endRow, int left, int right, int top, int bottom) {
        if (!hasSelection()) {
            resetSelectionUiRects();
            return;
        }

        SelectionAnchor orderedStart = getOrderedSelectionStart();
        SelectionAnchor orderedEnd = getOrderedSelectionEnd();
        if (orderedStart.rowIndex >= startRow && orderedStart.rowIndex < endRow) {
            updateHandleRect(selectionStartHandleRect, orderedStart, startRow, left);
        } else {
            selectionStartHandleRect.setEmpty();
        }
        if (orderedEnd.rowIndex >= startRow && orderedEnd.rowIndex < endRow) {
            updateHandleRect(selectionEndHandleRect, orderedEnd, startRow, left);
        } else {
            selectionEndHandleRect.setEmpty();
        }

        float buttonWidth = selectionButtonTextPaint.measureText("复制") + selectionButtonPaddingH * 2f;
        Paint.FontMetrics metrics = selectionButtonTextPaint.getFontMetrics();
        float buttonHeight = (metrics.descent - metrics.ascent) + selectionButtonPaddingV * 2f;
        float preferredCenterX;
        if (orderedStart.rowIndex == orderedEnd.rowIndex) {
            float startX = getBoundaryX(orderedStart.rowIndex, orderedStart.column, left);
            float endX = getBoundaryX(orderedEnd.rowIndex, orderedEnd.column, left);
            preferredCenterX = (startX + endX) * 0.5f;
        } else {
            preferredCenterX = (left + right) * 0.5f;
        }

        float maxLeft = Math.max(left, right - buttonWidth);
        float buttonLeft = Math.max(left, Math.min(preferredCenterX - buttonWidth / 2f, maxLeft));
        float selectionTop = top + (orderedStart.rowIndex - startRow) * charHeight;
        float buttonTop = selectionTop - buttonHeight - selectionButtonGap;
        if (buttonTop < top) {
            float selectionBottom = top + (orderedEnd.rowIndex - startRow + 1) * charHeight;
            buttonTop = Math.min(bottom - buttonHeight, selectionBottom + selectionButtonGap);
        }
        selectionButtonRect.set(buttonLeft, buttonTop,
                buttonLeft + buttonWidth, buttonTop + buttonHeight);
    }

    private void resetSelectionUiRects() {
        selectionStartHandleRect.setEmpty();
        selectionEndHandleRect.setEmpty();
        selectionButtonRect.setEmpty();
    }

    private void updateHandleRect(RectF rect, SelectionAnchor anchor, int startRow, int left) {
        float x = getBoundaryX(anchor.rowIndex, anchor.column, left);
        float lineTop = getPaddingTop() + (anchor.rowIndex - startRow + 1) * charHeight - dp(1);
        float circleCenterY = lineTop + selectionHandleStemHeight;
        rect.set(
                x - selectionHandleRadius * 1.8f,
                lineTop - selectionHandleRadius,
                x + selectionHandleRadius * 1.8f,
                circleCenterY + selectionHandleRadius
        );
    }

    private SelectionTouchTarget hitTestSelectionTarget(float x, float y) {
        if (selectionButtonRect.contains(x, y)) {
            return SelectionTouchTarget.COPY_BUTTON;
        }
        if (selectionStartHandleRect.contains(x, y)) {
            return SelectionTouchTarget.START_HANDLE;
        }
        if (selectionEndHandleRect.contains(x, y)) {
            return SelectionTouchTarget.END_HANDLE;
        }
        return SelectionTouchTarget.NONE;
    }

    private int resolveDisplayRowIndex(float y) {
        int totalRows = getTotalDisplayRows();
        if (totalRows <= 0) {
            return -1;
        }

        int startRow = getViewportStartRow(totalRows);
        int maxVisibleIndex = Math.max(0, Math.min(visibleRows - 1, totalRows - startRow - 1));
        int rowOffset = (int) ((y - getPaddingTop()) / Math.max(1f, charHeight));
        rowOffset = Math.max(0, Math.min(maxVisibleIndex, rowOffset));
        return Math.max(0, Math.min(totalRows - 1, startRow + rowOffset));
    }

    private int resolveColumnIndex(int rowIndex, float x, int left) {
        if (x <= left) {
            return 0;
        }

        float previousBoundary = left;
        for (int column = 0; column < TERM_COLUMNS; column++) {
            float nextBoundary = getBoundaryX(rowIndex, column + 1, left);
            if (x < (previousBoundary + nextBoundary) * 0.5f) {
                return column;
            }
            previousBoundary = nextBoundary;
        }
        return TERM_COLUMNS;
    }

    private float getBoundaryX(int rowIndex, int column, int left) {
        int safeColumn = Math.max(0, Math.min(TERM_COLUMNS, column));
        char[] row = getDisplayRow(rowIndex);
        return left + textPaint.measureText(row, 0, Math.min(safeColumn, row.length));
    }

    private int getRowTextStart(int rowIndex) {
        char[] row = getDisplayRow(rowIndex);
        int start = 0;
        while (start < row.length && row[start] == ' ') {
            start++;
        }
        return start;
    }

    private int getRowTextEnd(int rowIndex) {
        char[] row = getDisplayRow(rowIndex);
        int end = row.length;
        while (end > 0 && row[end - 1] == ' ') {
            end--;
        }
        return end;
    }

    private SelectionSegment getSelectionSegment(int rowIndex) {
        if (!hasSelection()) {
            return null;
        }

        SelectionAnchor orderedStart = getOrderedSelectionStart();
        SelectionAnchor orderedEnd = getOrderedSelectionEnd();
        if (rowIndex < orderedStart.rowIndex || rowIndex > orderedEnd.rowIndex) {
            return null;
        }

        int startColumn = rowIndex == orderedStart.rowIndex ? orderedStart.column : 0;
        int endColumn = rowIndex == orderedEnd.rowIndex ? orderedEnd.column : getRowTextEnd(rowIndex);
        startColumn = Math.max(0, Math.min(TERM_COLUMNS, startColumn));
        endColumn = Math.max(0, Math.min(TERM_COLUMNS, endColumn));
        if (endColumn <= startColumn) {
            return null;
        }
        return new SelectionSegment(startColumn, endColumn);
    }

    private SelectionAnchor getOrderedSelectionStart() {
        return compareSelectionAnchors(selectionStart, selectionEnd) <= 0
                ? selectionStart
                : selectionEnd;
    }

    private SelectionAnchor getOrderedSelectionEnd() {
        return compareSelectionAnchors(selectionStart, selectionEnd) <= 0
                ? selectionEnd
                : selectionStart;
    }

    private int compareSelectionAnchors(SelectionAnchor first, SelectionAnchor second) {
        if (first.rowIndex != second.rowIndex) {
            return first.rowIndex < second.rowIndex ? -1 : 1;
        }
        if (first.column == second.column) {
            return 0;
        }
        return first.column < second.column ? -1 : 1;
    }

    private String getSelectedText() {
        if (!hasSelection()) {
            return "";
        }

        SelectionAnchor orderedStart = getOrderedSelectionStart();
        SelectionAnchor orderedEnd = getOrderedSelectionEnd();
        StringBuilder out = new StringBuilder();
        for (int rowIndex = orderedStart.rowIndex; rowIndex <= orderedEnd.rowIndex; rowIndex++) {
            if (rowIndex > orderedStart.rowIndex) {
                out.append('\n');
            }

            char[] row = getDisplayRow(rowIndex);
            int startColumn = rowIndex == orderedStart.rowIndex ? orderedStart.column : 0;
            int endColumn = rowIndex == orderedEnd.rowIndex ? orderedEnd.column : getRowTextEnd(rowIndex);
            startColumn = Math.max(0, Math.min(row.length, startColumn));
            endColumn = Math.max(0, Math.min(row.length, endColumn));
            if (endColumn > startColumn) {
                out.append(row, startColumn, endColumn - startColumn);
            }
        }
        return out.toString();
    }

    private char[] getDisplayRow(int rowIndex) {
        return emulator.getDisplayRow(rowIndex);
    }

    private int[] getDisplayForegroundRow(int rowIndex) {
        return emulator.getDisplayForegroundRow(rowIndex);
    }

    private int[] getDisplayBackgroundRow(int rowIndex) {
        return emulator.getDisplayBackgroundRow(rowIndex);
    }

    private int findLastVisibleCell(char[] row, int[] bgRow) {
        int i = row.length;
        while (i > 0 && row[i - 1] == ' ' && bgRow[i - 1] == COLOR_BACKGROUND) {
            i--;
        }
        return i;
    }

    private boolean segmentHasVisibleText(char[] row, int start, int end) {
        for (int i = start; i < end; i++) {
            if (row[i] != ' ') {
                return true;
            }
        }
        return false;
    }

    private void drawCursor(Canvas canvas, CursorPosition cursor, int startRow, int left, int top) {
        float cursorY = top + (cursor.rowIndex - startRow) * charHeight;
        float cursorX = getCursorBoundaryX(cursor, left);
        switch (emulator.getCursorStyle()) {
            case BLOCK:
                float blockWidth = getColumnAdvance(cursor.rowIndex, cursor.column);
                int originalColor = cursorPaint.getColor();
                cursorPaint.setColor(0x665FE68A);
                canvas.drawRect(cursorX, cursorY + dp(2), cursorX + blockWidth,
                        cursorY + charHeight - dp(2), cursorPaint);
                cursorPaint.setColor(originalColor);
                break;
            case UNDERLINE:
                canvas.drawRect(cursorX, cursorY + charHeight - dp(3),
                        cursorX + getColumnAdvance(cursor.rowIndex, cursor.column),
                        cursorY + charHeight - dp(1), cursorPaint);
                break;
            case BAR:
            default:
                canvas.drawRect(cursorX, cursorY + dp(3), cursorX + cursorThickness,
                        cursorY + charHeight - dp(3), cursorPaint);
                break;
        }
    }

    private float getCursorBoundaryX(CursorPosition cursor, int left) {
        return getBoundaryX(cursor.rowIndex, cursor.column, left);
    }

    private float getColumnAdvance(int rowIndex, int column) {
        int safeColumn = Math.max(0, Math.min(TERM_COLUMNS - 1, column));
        char[] row = getDisplayRow(rowIndex);
        if (row.length == 0 || safeColumn >= row.length) {
            return charWidth;
        }
        float width = textPaint.measureText(row, safeColumn, 1);
        return width > 0f ? width : charWidth;
    }

    private CursorPosition getCursorPosition() {
        int rowIndex = emulator.getCursorRowIndexForDisplay();
        int column = emulator.getCursorColumn();
        if (!emulator.isAlternateScreenActive() && composingInput.length() > 0) {
            int offset = column + composingInput.length();
            rowIndex += offset / TERM_COLUMNS;
            column = offset % TERM_COLUMNS;
        }
        return new CursorPosition(rowIndex, column);
    }

    private String repeatBackspace(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append('\b');
        }
        return sb.toString();
    }

    private void toast(String message) {
        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                getResources().getDisplayMetrics());
    }

    private class TerminalGestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            stopFling();
            requestFocus();
            return true;
        }

        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            showSoftInput();
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            beginLineSelection(e);
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            if (scaleActive) {
                return false;
            }
            scrollByGesture(-distanceY);
            return !emulator.isAlternateScreenActive();
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            if (scaleActive || emulator.isAlternateScreenActive()) {
                return false;
            }
            startFling(velocityY);
            return true;
        }
    }

    private class TerminalScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            scaleActive = true;
            return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float nextSize = fontSizeSp * detector.getScaleFactor();
            nextSize = Math.max(MIN_FONT_SP, Math.min(MAX_FONT_SP, nextSize));
            if (Math.abs(nextSize - fontSizeSp) < 0.05f) {
                return true;
            }
            fontSizeSp = nextSize;
            updateTextMetrics();
            requestLayout();
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            scaleActive = false;
        }
    }

    private static class CursorPosition {
        final int rowIndex;
        final int column;

        CursorPosition(int rowIndex, int column) {
            this.rowIndex = rowIndex;
            this.column = column;
        }
    }

    private static class SelectionAnchor {
        final int rowIndex;
        final int column;

        SelectionAnchor(int rowIndex, int column) {
            this.rowIndex = rowIndex;
            this.column = column;
        }
    }

    private static class SelectionSegment {
        final int startColumn;
        final int endColumn;

        SelectionSegment(int startColumn, int endColumn) {
            this.startColumn = startColumn;
            this.endColumn = endColumn;
        }
    }

    private enum SelectionTouchTarget {
        NONE,
        START_HANDLE,
        END_HANDLE,
        COPY_BUTTON
    }

    private enum CursorStyle {
        BAR,
        BLOCK,
        UNDERLINE
    }

    private static class TerminalInputConnection extends BaseInputConnection {
        private final TerminalView view;
        private final Terminal terminal;

        TerminalInputConnection(TerminalView view, Terminal terminal) {
            super(view, true);
            this.view = view;
            this.terminal = terminal;
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            if (terminal != null && text != null && text.length() > 0) {
                view.commitInputText(text.toString());
                return true;
            }
            return false;
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            view.setComposingText(text);
            return true;
        }

        @Override
        public boolean finishComposingText() {
            view.clearComposingText();
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            if (terminal != null) {
                view.deleteBeforeCursor(Math.max(1, beforeLength));
                return true;
            }
            return false;
        }

        @Override
        public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
            if (terminal != null) {
                view.deleteBeforeCursor(Math.max(1, beforeLength));
                return true;
            }
            return false;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                view.onKeyDown(event.getKeyCode(), event);
            }
            return true;
        }
    }

    private static class TerminalEmulator {
        private static final int COLOR_MODE_DEFAULT = 0;
        private static final int COLOR_MODE_PALETTE = 1;
        private static final int COLOR_MODE_RGB = 2;

        private final int rows;
        private final int cols;
        private final int maxScrollback;
        private final char[][] mainScreen;
        private final char[][] altScreen;
        private final int[][] mainForeground;
        private final int[][] mainBackground;
        private final int[][] altForeground;
        private final int[][] altBackground;
        private final List<StyledRow> scrollback = new ArrayList<StyledRow>();

        private char[][] activeScreen;
        private int[][] activeForeground;
        private int[][] activeBackground;
        private boolean alternateScreen;
        private boolean cursorVisible = true;
        private CursorStyle cursorStyle = CursorStyle.BAR;
        private int cursorRow;
        private int cursorCol;
        private int savedCursorRow;
        private int savedCursorCol;
        private int scrollTop;
        private int scrollBottom;
        private ParseState parseState = ParseState.NORMAL;
        private final StringBuilder escapeBuffer = new StringBuilder();
        private int foregroundMode;
        private int foregroundValue;
        private int backgroundMode;
        private int backgroundValue;
        private boolean bold;
        private boolean inverse;

        TerminalEmulator(int rows, int cols, int maxScrollback) {
            this.rows = rows;
            this.cols = cols;
            this.maxScrollback = maxScrollback;
            mainScreen = new char[rows][cols];
            altScreen = new char[rows][cols];
            mainForeground = new int[rows][cols];
            mainBackground = new int[rows][cols];
            altForeground = new int[rows][cols];
            altBackground = new int[rows][cols];
            activeScreen = mainScreen;
            activeForeground = mainForeground;
            activeBackground = mainBackground;
            clearScreen(mainScreen, mainForeground, mainBackground);
            clearScreen(altScreen, altForeground, altBackground);
            resetState();
        }

        String getAllText() {
            StringBuilder out = new StringBuilder();
            int total = getDisplayRowCount();
            for (int i = 0; i < total; i++) {
                if (i > 0) {
                    out.append('\n');
                }
                char[] row = getDisplayRow(i);
                out.append(row, 0, trimRow(row));
            }
            return out.toString();
        }

        boolean isAlternateScreenActive() {
            return alternateScreen;
        }

        boolean isCursorVisible() {
            return cursorVisible;
        }

        CursorStyle getCursorStyle() {
            return cursorStyle;
        }

        int getCursorRowIndexForDisplay() {
            return alternateScreen ? cursorRow : scrollback.size() + cursorRow;
        }

        int getCursorColumn() {
            return cursorCol;
        }

        int getDisplayRowCount() {
            return alternateScreen ? rows : scrollback.size() + rows;
        }

        char[] getDisplayRow(int index) {
            if (alternateScreen) {
                return altScreen[clamp(index, 0, rows - 1)];
            }
            if (index < scrollback.size()) {
                return scrollback.get(index).chars;
            }
            return mainScreen[clamp(index - scrollback.size(), 0, rows - 1)];
        }

        int[] getDisplayForegroundRow(int index) {
            if (alternateScreen) {
                return altForeground[clamp(index, 0, rows - 1)];
            }
            if (index < scrollback.size()) {
                return scrollback.get(index).foreground;
            }
            return mainForeground[clamp(index - scrollback.size(), 0, rows - 1)];
        }

        int[] getDisplayBackgroundRow(int index) {
            if (alternateScreen) {
                return altBackground[clamp(index, 0, rows - 1)];
            }
            if (index < scrollback.size()) {
                return scrollback.get(index).background;
            }
            return mainBackground[clamp(index - scrollback.size(), 0, rows - 1)];
        }

        void append(String text) {
            for (int i = 0; i < text.length(); i++) {
                handleChar(text.charAt(i));
            }
        }

        private void handleChar(char c) {
            switch (parseState) {
                case NORMAL:
                    handleNormalChar(c);
                    return;
                case ESCAPE:
                    handleEscapeChar(c);
                    return;
                case CSI:
                    handleCsiChar(c);
                    return;
                case OSC:
                    handleOscChar(c);
                    return;
                case OSC_ESC:
                    if (c == '\\') {
                        parseState = ParseState.NORMAL;
                    } else {
                        parseState = ParseState.OSC;
                    }
                    return;
                case ESCAPE_IGNORE_ONE:
                    parseState = ParseState.NORMAL;
                    return;
                default:
                    parseState = ParseState.NORMAL;
            }
        }

        private void handleNormalChar(char c) {
            switch (c) {
                case 27:
                    parseState = ParseState.ESCAPE;
                    return;
                case '\n':
                    lineFeed();
                    return;
                case '\r':
                    cursorCol = 0;
                    return;
                case '\b':
                    if (cursorCol > 0) {
                        cursorCol--;
                    }
                    return;
                case '\t':
                    cursorCol = Math.min(cols - 1, ((cursorCol / 8) + 1) * 8);
                    return;
                default:
                    if (c >= 32 && c != 127) {
                        putChar(c);
                    }
            }
        }

        private void handleEscapeChar(char c) {
            switch (c) {
                case '[':
                    escapeBuffer.setLength(0);
                    parseState = ParseState.CSI;
                    return;
                case ']':
                    escapeBuffer.setLength(0);
                    parseState = ParseState.OSC;
                    return;
                case '7':
                    saveCursor();
                    parseState = ParseState.NORMAL;
                    return;
                case '8':
                    restoreCursor();
                    parseState = ParseState.NORMAL;
                    return;
                case 'D':
                    lineFeed();
                    parseState = ParseState.NORMAL;
                    return;
                case 'E':
                    lineFeed();
                    cursorCol = 0;
                    parseState = ParseState.NORMAL;
                    return;
                case 'M':
                    reverseIndex();
                    parseState = ParseState.NORMAL;
                    return;
                case 'c':
                    fullReset();
                    parseState = ParseState.NORMAL;
                    return;
                case '(':
                case ')':
                case '*':
                case '+':
                    parseState = ParseState.ESCAPE_IGNORE_ONE;
                    return;
                default:
                    parseState = ParseState.NORMAL;
            }
        }

        private void handleCsiChar(char c) {
            if (c >= '@' && c <= '~') {
                String params = escapeBuffer.toString();
                escapeBuffer.setLength(0);
                parseState = ParseState.NORMAL;
                applyCsi(params, c);
                return;
            }
            escapeBuffer.append(c);
        }

        private void handleOscChar(char c) {
            if (c == 7) {
                parseState = ParseState.NORMAL;
            } else if (c == 27) {
                parseState = ParseState.OSC_ESC;
            }
        }

        private void applyCsi(String rawParams, char command) {
            boolean privateMode = rawParams.startsWith("?");
            String params = privateMode ? rawParams.substring(1) : rawParams;
            int[] values = parseCsiValues(params);

            if (privateMode) {
                applyPrivateMode(values, command);
                return;
            }

            switch (command) {
                case 'A':
                    cursorRow = Math.max(scrollTop, cursorRow - getValue(values, 0, 1));
                    break;
                case 'B':
                    cursorRow = Math.min(scrollBottom, cursorRow + getValue(values, 0, 1));
                    break;
                case 'C':
                    cursorCol = Math.min(cols - 1, cursorCol + getValue(values, 0, 1));
                    break;
                case 'D':
                    cursorCol = Math.max(0, cursorCol - getValue(values, 0, 1));
                    break;
                case 'E':
                    cursorRow = Math.min(scrollBottom, cursorRow + getValue(values, 0, 1));
                    cursorCol = 0;
                    break;
                case 'F':
                    cursorRow = Math.max(scrollTop, cursorRow - getValue(values, 0, 1));
                    cursorCol = 0;
                    break;
                case 'G':
                    cursorCol = clamp(getValue(values, 0, 1) - 1, 0, cols - 1);
                    break;
                case 'H':
                case 'f':
                    cursorRow = clamp(getValue(values, 0, 1) - 1, 0, rows - 1);
                    cursorCol = clamp(getValue(values, 1, 1) - 1, 0, cols - 1);
                    break;
                case 'J':
                    eraseDisplay(getValue(values, 0, 0));
                    break;
                case 'K':
                    eraseLine(getValue(values, 0, 0));
                    break;
                case 'L':
                    insertLines(getValue(values, 0, 1));
                    break;
                case 'M':
                    deleteLines(getValue(values, 0, 1));
                    break;
                case '@':
                    insertChars(getValue(values, 0, 1));
                    break;
                case 'P':
                    deleteChars(getValue(values, 0, 1));
                    break;
                case 'X':
                    eraseChars(getValue(values, 0, 1));
                    break;
                case 'S':
                    scrollUp(getValue(values, 0, 1));
                    break;
                case 'T':
                    scrollDown(getValue(values, 0, 1));
                    break;
                case 'd':
                    cursorRow = clamp(getValue(values, 0, 1) - 1, 0, rows - 1);
                    break;
                case 'm':
                    applySgr(values);
                    break;
                case 'q':
                    if (rawParams.indexOf(' ') >= 0) {
                        applyCursorStyle(values);
                    }
                    break;
                case 'r':
                    scrollTop = clamp(getValue(values, 0, 1) - 1, 0, rows - 1);
                    scrollBottom = clamp(getValue(values, 1, rows) - 1, 0, rows - 1);
                    if (scrollTop >= scrollBottom) {
                        scrollTop = 0;
                        scrollBottom = rows - 1;
                    }
                    cursorRow = scrollTop;
                    cursorCol = 0;
                    break;
                case 's':
                    saveCursor();
                    break;
                case 'u':
                    restoreCursor();
                    break;
                default:
                    break;
            }
        }

        private void applyCursorStyle(int[] values) {
            int styleValue = values.length == 0 ? 0 : values[0];
            switch (styleValue) {
                case 0:
                case 1:
                case 2:
                    cursorStyle = CursorStyle.BLOCK;
                    break;
                case 3:
                case 4:
                    cursorStyle = CursorStyle.UNDERLINE;
                    break;
                case 5:
                case 6:
                default:
                    cursorStyle = CursorStyle.BAR;
                    break;
            }
        }

        private void applySgr(int[] values) {
            for (int i = 0; i < values.length; i++) {
                int value = values[i];
                switch (value) {
                    case 0:
                        resetAttributes();
                        break;
                    case 1:
                        bold = true;
                        break;
                    case 22:
                        bold = false;
                        break;
                    case 7:
                        inverse = true;
                        break;
                    case 27:
                        inverse = false;
                        break;
                    case 39:
                        foregroundMode = COLOR_MODE_DEFAULT;
                        foregroundValue = 0;
                        break;
                    case 49:
                        backgroundMode = COLOR_MODE_DEFAULT;
                        backgroundValue = 0;
                        break;
                    case 38:
                    case 48:
                        i = applyExtendedColor(values, i, value == 38);
                        break;
                    default:
                        if (value >= 30 && value <= 37) {
                            foregroundMode = COLOR_MODE_PALETTE;
                            foregroundValue = value - 30;
                        } else if (value >= 90 && value <= 97) {
                            foregroundMode = COLOR_MODE_PALETTE;
                            foregroundValue = value - 90 + 8;
                        } else if (value >= 40 && value <= 47) {
                            backgroundMode = COLOR_MODE_PALETTE;
                            backgroundValue = value - 40;
                        } else if (value >= 100 && value <= 107) {
                            backgroundMode = COLOR_MODE_PALETTE;
                            backgroundValue = value - 100 + 8;
                        }
                        break;
                }
            }
        }

        private int applyExtendedColor(int[] values, int startIndex, boolean foreground) {
            int modeIndex = startIndex + 1;
            if (modeIndex >= values.length) {
                return startIndex;
            }

            int mode = values[modeIndex];
            if (mode == 5 && modeIndex + 1 < values.length) {
                setIndexedColor(values[modeIndex + 1], foreground);
                return modeIndex + 1;
            }
            if (mode == 2 && modeIndex + 3 < values.length) {
                setRgbColor(values[modeIndex + 1], values[modeIndex + 2], values[modeIndex + 3],
                        foreground);
                return modeIndex + 3;
            }
            return startIndex;
        }

        private void setIndexedColor(int colorIndex, boolean foreground) {
            if (foreground) {
                foregroundMode = COLOR_MODE_PALETTE;
                foregroundValue = clamp(colorIndex, 0, 255);
            } else {
                backgroundMode = COLOR_MODE_PALETTE;
                backgroundValue = clamp(colorIndex, 0, 255);
            }
        }

        private void setRgbColor(int red, int green, int blue, boolean foreground) {
            int color = 0xFF000000
                    | (clamp(red, 0, 255) << 16)
                    | (clamp(green, 0, 255) << 8)
                    | clamp(blue, 0, 255);
            if (foreground) {
                foregroundMode = COLOR_MODE_RGB;
                foregroundValue = color;
            } else {
                backgroundMode = COLOR_MODE_RGB;
                backgroundValue = color;
            }
        }

        private void resetAttributes() {
            foregroundMode = COLOR_MODE_DEFAULT;
            foregroundValue = 0;
            backgroundMode = COLOR_MODE_DEFAULT;
            backgroundValue = 0;
            bold = false;
            inverse = false;
        }

        private int getCurrentForegroundColor() {
            int foreground = resolveActiveColor(foregroundMode, foregroundValue, true);
            int background = resolveActiveColor(backgroundMode, backgroundValue, false);
            return inverse ? background : foreground;
        }

        private int getCurrentBackgroundColor() {
            int foreground = resolveActiveColor(foregroundMode, foregroundValue, true);
            int background = resolveActiveColor(backgroundMode, backgroundValue, false);
            return inverse ? foreground : background;
        }

        private int resolveActiveColor(int mode, int value, boolean foreground) {
            switch (mode) {
                case COLOR_MODE_PALETTE:
                    int index = clamp(value, 0, 255);
                    if (foreground && bold && index >= 0 && index < 8) {
                        index += 8;
                    }
                    return resolveIndexedColor(index);
                case COLOR_MODE_RGB:
                    return value;
                case COLOR_MODE_DEFAULT:
                default:
                    return foreground ? COLOR_TEXT : COLOR_BACKGROUND;
            }
        }

        private int resolveIndexedColor(int index) {
            if (index < ANSI_PALETTE.length) {
                return ANSI_PALETTE[index];
            }
            if (index >= 16 && index <= 231) {
                int value = index - 16;
                int red = value / 36;
                int green = (value / 6) % 6;
                int blue = value % 6;
                return 0xFF000000
                        | (cubeComponent(red) << 16)
                        | (cubeComponent(green) << 8)
                        | cubeComponent(blue);
            }
            if (index >= 232 && index <= 255) {
                int shade = 8 + (index - 232) * 10;
                return 0xFF000000 | (shade << 16) | (shade << 8) | shade;
            }
            return COLOR_TEXT;
        }

        private int cubeComponent(int value) {
            return value <= 0 ? 0 : 55 + value * 40;
        }

        private void applyPrivateMode(int[] values, char command) {
            for (int value : values) {
                switch (value) {
                    case 25:
                        cursorVisible = command == 'h';
                        break;
                    case 47:
                    case 1047:
                    case 1049:
                        if (command == 'h') {
                            enterAlternateScreen();
                        } else if (command == 'l') {
                            exitAlternateScreen();
                        }
                        break;
                    default:
                        break;
                }
            }
        }

        private void enterAlternateScreen() {
            if (alternateScreen) {
                return;
            }
            saveCursor();
            alternateScreen = true;
            activeScreen = altScreen;
            activeForeground = altForeground;
            activeBackground = altBackground;
            clearScreen(altScreen, altForeground, altBackground);
            cursorRow = 0;
            cursorCol = 0;
            scrollTop = 0;
            scrollBottom = rows - 1;
            cursorVisible = true;
        }

        private void exitAlternateScreen() {
            if (!alternateScreen) {
                return;
            }
            alternateScreen = false;
            activeScreen = mainScreen;
            activeForeground = mainForeground;
            activeBackground = mainBackground;
            restoreCursor();
            scrollTop = 0;
            scrollBottom = rows - 1;
            cursorVisible = true;
        }

        private void lineFeed() {
            if (cursorRow == scrollBottom) {
                scrollUp(1);
            } else if (cursorRow < rows - 1) {
                cursorRow++;
            }
        }

        private void reverseIndex() {
            if (cursorRow == scrollTop) {
                scrollDown(1);
            } else if (cursorRow > 0) {
                cursorRow--;
            }
        }

        private void putChar(char c) {
            if (cursorCol < 0) {
                cursorCol = 0;
            }
            if (cursorCol >= cols) {
                cursorCol = 0;
                lineFeed();
            }
            activeScreen[cursorRow][cursorCol] = c;
            activeForeground[cursorRow][cursorCol] = getCurrentForegroundColor();
            activeBackground[cursorRow][cursorCol] = getCurrentBackgroundColor();
            cursorCol++;
            if (cursorCol >= cols) {
                cursorCol = 0;
                lineFeed();
            }
        }

        private void scrollUp(int count) {
            int lines = Math.max(1, count);
            while (lines-- > 0) {
                if (!alternateScreen && scrollTop == 0 && scrollBottom == rows - 1) {
                    pushScrollback(copyStyledRow(activeScreen[0], activeForeground[0],
                            activeBackground[0]));
                }
                for (int row = scrollTop; row < scrollBottom; row++) {
                    copyRowInto(activeScreen[row + 1], activeForeground[row + 1],
                            activeBackground[row + 1], activeScreen[row], activeForeground[row],
                            activeBackground[row]);
                }
                clearRow(activeScreen[scrollBottom], activeForeground[scrollBottom],
                        activeBackground[scrollBottom]);
            }
        }

        private void scrollDown(int count) {
            int lines = Math.max(1, count);
            while (lines-- > 0) {
                for (int row = scrollBottom; row > scrollTop; row--) {
                    copyRowInto(activeScreen[row - 1], activeForeground[row - 1],
                            activeBackground[row - 1], activeScreen[row], activeForeground[row],
                            activeBackground[row]);
                }
                clearRow(activeScreen[scrollTop], activeForeground[scrollTop],
                        activeBackground[scrollTop]);
            }
        }

        private void eraseDisplay(int mode) {
            switch (mode) {
                case 0:
                    eraseLineFrom(cursorRow, cursorCol, cols - 1);
                    for (int row = cursorRow + 1; row < rows; row++) {
                        clearRow(activeScreen[row], activeForeground[row], activeBackground[row]);
                    }
                    break;
                case 1:
                    for (int row = 0; row < cursorRow; row++) {
                        clearRow(activeScreen[row], activeForeground[row], activeBackground[row]);
                    }
                    eraseLineFrom(cursorRow, 0, cursorCol);
                    break;
                case 2:
                default:
                    clearScreen(activeScreen, activeForeground, activeBackground);
                    break;
            }
        }

        private void eraseLine(int mode) {
            switch (mode) {
                case 0:
                    eraseLineFrom(cursorRow, cursorCol, cols - 1);
                    break;
                case 1:
                    eraseLineFrom(cursorRow, 0, cursorCol);
                    break;
                case 2:
                default:
                    clearRow(activeScreen[cursorRow], activeForeground[cursorRow],
                            activeBackground[cursorRow]);
                    break;
            }
        }

        private void insertLines(int count) {
            if (cursorRow < scrollTop || cursorRow > scrollBottom) {
                return;
            }
            int lines = Math.max(1, count);
            while (lines-- > 0) {
                for (int row = scrollBottom; row > cursorRow; row--) {
                    copyRowInto(activeScreen[row - 1], activeForeground[row - 1],
                            activeBackground[row - 1], activeScreen[row], activeForeground[row],
                            activeBackground[row]);
                }
                clearRow(activeScreen[cursorRow], activeForeground[cursorRow],
                        activeBackground[cursorRow]);
            }
        }

        private void deleteLines(int count) {
            if (cursorRow < scrollTop || cursorRow > scrollBottom) {
                return;
            }
            int lines = Math.max(1, count);
            while (lines-- > 0) {
                for (int row = cursorRow; row < scrollBottom; row++) {
                    copyRowInto(activeScreen[row + 1], activeForeground[row + 1],
                            activeBackground[row + 1], activeScreen[row], activeForeground[row],
                            activeBackground[row]);
                }
                clearRow(activeScreen[scrollBottom], activeForeground[scrollBottom],
                        activeBackground[scrollBottom]);
            }
        }

        private void insertChars(int count) {
            char[] row = activeScreen[cursorRow];
            int[] fgRow = activeForeground[cursorRow];
            int[] bgRow = activeBackground[cursorRow];
            int chars = Math.max(1, count);
            for (int i = cols - 1; i >= cursorCol + chars; i--) {
                row[i] = row[i - chars];
                fgRow[i] = fgRow[i - chars];
                bgRow[i] = bgRow[i - chars];
            }
            for (int i = cursorCol; i < Math.min(cols, cursorCol + chars); i++) {
                row[i] = ' ';
                fgRow[i] = COLOR_TEXT;
                bgRow[i] = COLOR_BACKGROUND;
            }
        }

        private void deleteChars(int count) {
            char[] row = activeScreen[cursorRow];
            int[] fgRow = activeForeground[cursorRow];
            int[] bgRow = activeBackground[cursorRow];
            int chars = Math.max(1, count);
            for (int i = cursorCol; i < cols - chars; i++) {
                row[i] = row[i + chars];
                fgRow[i] = fgRow[i + chars];
                bgRow[i] = bgRow[i + chars];
            }
            for (int i = Math.max(cursorCol, cols - chars); i < cols; i++) {
                row[i] = ' ';
                fgRow[i] = COLOR_TEXT;
                bgRow[i] = COLOR_BACKGROUND;
            }
        }

        private void eraseChars(int count) {
            char[] row = activeScreen[cursorRow];
            int[] fgRow = activeForeground[cursorRow];
            int[] bgRow = activeBackground[cursorRow];
            int chars = Math.max(1, count);
            for (int i = cursorCol; i < Math.min(cols, cursorCol + chars); i++) {
                row[i] = ' ';
                fgRow[i] = COLOR_TEXT;
                bgRow[i] = COLOR_BACKGROUND;
            }
        }

        private void saveCursor() {
            savedCursorRow = cursorRow;
            savedCursorCol = cursorCol;
        }

        private void restoreCursor() {
            cursorRow = clamp(savedCursorRow, 0, rows - 1);
            cursorCol = clamp(savedCursorCol, 0, cols - 1);
        }

        private void fullReset() {
            clearScreen(mainScreen, mainForeground, mainBackground);
            clearScreen(altScreen, altForeground, altBackground);
            scrollback.clear();
            activeScreen = alternateScreen ? altScreen : mainScreen;
            activeForeground = alternateScreen ? altForeground : mainForeground;
            activeBackground = alternateScreen ? altBackground : mainBackground;
            resetState();
        }

        private void resetState() {
            cursorRow = 0;
            cursorCol = 0;
            savedCursorRow = 0;
            savedCursorCol = 0;
            scrollTop = 0;
            scrollBottom = rows - 1;
            cursorVisible = true;
            cursorStyle = CursorStyle.BAR;
            parseState = ParseState.NORMAL;
            escapeBuffer.setLength(0);
            resetAttributes();
        }

        private void pushScrollback(StyledRow row) {
            if (maxScrollback <= 0) {
                return;
            }
            scrollback.add(row);
            while (scrollback.size() > maxScrollback) {
                scrollback.remove(0);
            }
        }

        private int[] parseCsiValues(String params) {
            if (params == null || params.length() == 0) {
                return new int[]{0};
            }
            String[] parts = params.split(";", -1);
            int[] values = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i].trim();
                if (part.length() == 0) {
                    values[i] = 0;
                } else {
                    try {
                        values[i] = Integer.parseInt(part);
                    } catch (NumberFormatException e) {
                        values[i] = 0;
                    }
                }
            }
            return values;
        }

        private int getValue(int[] values, int index, int fallback) {
            if (index >= values.length) {
                return fallback;
            }
            return values[index] == 0 ? fallback : values[index];
        }

        private void clearScreen(char[][] screen, int[][] foreground, int[][] background) {
            for (int row = 0; row < screen.length; row++) {
                clearRow(screen[row], foreground[row], background[row]);
            }
        }

        private void clearRow(char[] row, int[] foregroundRow, int[] backgroundRow) {
            for (int i = 0; i < row.length; i++) {
                row[i] = ' ';
                foregroundRow[i] = COLOR_TEXT;
                backgroundRow[i] = COLOR_BACKGROUND;
            }
        }

        private void eraseLineFrom(int rowIndex, int start, int end) {
            char[] row = activeScreen[rowIndex];
            int[] fgRow = activeForeground[rowIndex];
            int[] bgRow = activeBackground[rowIndex];
            int from = clamp(start, 0, cols - 1);
            int to = clamp(end, 0, cols - 1);
            for (int i = from; i <= to; i++) {
                row[i] = ' ';
                fgRow[i] = COLOR_TEXT;
                bgRow[i] = COLOR_BACKGROUND;
            }
        }

        private StyledRow copyStyledRow(char[] chars, int[] foreground, int[] background) {
            return new StyledRow(copyRow(chars), copyIntRow(foreground), copyIntRow(background));
        }

        private char[] copyRow(char[] source) {
            char[] copy = new char[source.length];
            System.arraycopy(source, 0, copy, 0, source.length);
            return copy;
        }

        private int[] copyIntRow(int[] source) {
            int[] copy = new int[source.length];
            System.arraycopy(source, 0, copy, 0, source.length);
            return copy;
        }

        private void copyRowInto(char[] sourceChars, int[] sourceForeground, int[] sourceBackground,
                                 char[] targetChars, int[] targetForeground,
                                 int[] targetBackground) {
            System.arraycopy(sourceChars, 0, targetChars, 0, sourceChars.length);
            System.arraycopy(sourceForeground, 0, targetForeground, 0, sourceForeground.length);
            System.arraycopy(sourceBackground, 0, targetBackground, 0, sourceBackground.length);
        }

        private int trimRow(char[] row) {
            int end = row.length;
            while (end > 0 && row[end - 1] == ' ') {
                end--;
            }
            return end;
        }

        private int clamp(int value, int min, int max) {
            if (value < min) {
                return min;
            }
            if (value > max) {
                return max;
            }
            return value;
        }

        private static class StyledRow {
            final char[] chars;
            final int[] foreground;
            final int[] background;

            StyledRow(char[] chars, int[] foreground, int[] background) {
                this.chars = chars;
                this.foreground = foreground;
                this.background = background;
            }
        }

        private enum ParseState {
            NORMAL,
            ESCAPE,
            CSI,
            OSC,
            OSC_ESC,
            ESCAPE_IGNORE_ONE
        }
    }
}
