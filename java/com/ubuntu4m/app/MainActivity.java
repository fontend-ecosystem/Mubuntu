package com.ubuntu4m.app;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int COLOR_BACKGROUND = 0xFF0B0F10;
    private static final int COLOR_PANEL = 0xFF11161C;
    private static final int COLOR_PANEL_ALT = 0xFF161C23;
    private static final int COLOR_BORDER = 0xFF243040;
    private static final int COLOR_TEXT = 0xFFE6EDF3;
    private static final int COLOR_MUTED = 0xFF8B949E;
    private static final int COLOR_ACCENT = 0xFF5FE68A;
    private static final int COLOR_ACTIVE = 0xFF1A2430;
    private static final int COLOR_DANGER = 0xFFFF8E72;

    private final List<SessionRecord> sessions = new ArrayList<SessionRecord>();

    private FrameLayout terminalPane;
    private View sidebarPane;
    private LinearLayout sessionList;
    private TextView sessionToggleButton;

    private SessionRecord activeSession;
    private int nextSessionId = 1;
    private boolean sidebarVisible;

    private static class SessionRecord {
        final int id;
        final String name;
        final TerminalView terminalView;
        Terminal terminal;
        boolean exited;

        SessionRecord(int id, String name, TerminalView terminalView) {
            this.id = id;
            this.name = name;
            this.terminalView = terminalView;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        configureWindow();
        setContentView(buildContentView());
        setSidebarVisible(false);
        addSession(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        for (SessionRecord session : new ArrayList<SessionRecord>(sessions)) {
            if (session.terminal != null) {
                session.terminal.destroy();
            }
        }
    }

    private View buildContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BACKGROUND);

        LinearLayout mainArea = new LinearLayout(this);
        mainArea.setOrientation(LinearLayout.HORIZONTAL);
        mainArea.setBackgroundColor(COLOR_BACKGROUND);

        LinearLayout.LayoutParams sidebarParams = new LinearLayout.LayoutParams(
                dp(122), LinearLayout.LayoutParams.MATCH_PARENT);
        sidebarPane = buildSidebar();
        mainArea.addView(sidebarPane, sidebarParams);

        terminalPane = new FrameLayout(this);
        terminalPane.setBackgroundColor(COLOR_BACKGROUND);
        LinearLayout.LayoutParams terminalParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT);
        terminalParams.weight = 1f;
        mainArea.addView(terminalPane, terminalParams);

        LinearLayout.LayoutParams mainAreaParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0);
        mainAreaParams.weight = 1f;
        root.addView(mainArea, mainAreaParams);

        root.addView(buildExtraKeysBar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        return root;
    }

    private View buildSidebar() {
        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setBackgroundColor(COLOR_PANEL_ALT);
        sidebar.setPadding(dp(10), dp(10), dp(10), dp(10));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("SESS");
        title.setTextColor(COLOR_MUTED);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);

        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.weight = 1f;
        header.addView(title, titleParams);

        header.addView(createSidebarAction("+", COLOR_ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addSession(true);
            }
        }));

        sidebar.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scrollView = new ScrollView(this);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        sessionList = new LinearLayout(this);
        sessionList.setOrientation(LinearLayout.VERTICAL);
        sessionList.setPadding(0, dp(10), 0, dp(10));

        scrollView.addView(sessionList, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0);
        scrollParams.weight = 1f;
        sidebar.addView(scrollView, scrollParams);

        TextView tip = new TextView(this);
        tip.setText("tap switch\nx close");
        tip.setTextColor(COLOR_MUTED);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        tip.setTypeface(Typeface.MONOSPACE);
        sidebar.addView(tip);

        return sidebar;
    }

    private View buildExtraKeysBar() {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setBackgroundColor(COLOR_PANEL);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setFillViewport(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(6), dp(8), dp(8));

        sessionToggleButton = createKeyButton("≡", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setSidebarVisible(!sidebarVisible);
            }
        }, true);
        bar.addView(sessionToggleButton, buildKeyBarItemParams(false));

        bar.addView(createKeyButton("ESC", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendActiveInput("\033");
            }
        }, false), buildKeyBarItemParams(false));
        bar.addView(createKeyButton("TAB", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendActiveInput("\t");
            }
        }, false), buildKeyBarItemParams(false));
        bar.addView(createKeyButton("CTRL+C", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendActiveControlKey('c');
            }
        }, true), buildKeyBarItemParams(false));
        bar.addView(createKeyButton("CTRL+D", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendActiveControlKey('d');
            }
        }, true), buildKeyBarItemParams(false));
        bar.addView(createKeyButton("\u2190", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendActiveInput("\033[D");
            }
        }, false), buildKeyBarItemParams(false));
        bar.addView(createKeyButton("\u2193", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendActiveInput("\033[B");
            }
        }, false), buildKeyBarItemParams(false));
        bar.addView(createKeyButton("\u2191", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendActiveInput("\033[A");
            }
        }, false), buildKeyBarItemParams(false));
        bar.addView(createKeyButton("\u2192", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendActiveInput("\033[C");
            }
        }, false), buildKeyBarItemParams(false));
        bar.addView(createKeyButton("PGUP", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                withActiveTerminal(new TerminalAction() {
                    @Override
                    public void run(TerminalView view) {
                        view.pageUp();
                    }
                });
            }
        }, false), buildKeyBarItemParams(false));
        bar.addView(createKeyButton("PGDN", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                withActiveTerminal(new TerminalAction() {
                    @Override
                    public void run(TerminalView view) {
                        view.pageDown();
                    }
                });
            }
        }, false), buildKeyBarItemParams(false));
        bar.addView(createKeyButton("PASTE", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                withActiveTerminal(new TerminalAction() {
                    @Override
                    public void run(TerminalView view) {
                        view.pasteFromClipboard();
                    }
                });
            }
        }, true), buildKeyBarItemParams(false));
        bar.addView(createKeyButton("KEYBD", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                withActiveTerminal(new TerminalAction() {
                    @Override
                    public void run(TerminalView view) {
                        view.showKeyboard();
                    }
                });
            }
        }, true), buildKeyBarItemParams(true));

        scroller.addView(bar, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.WRAP_CONTENT));
        return scroller;
    }

    private LinearLayout.LayoutParams buildKeyBarItemParams(boolean last) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.rightMargin = last ? 0 : dp(4);
        return params;
    }

    private TextView createKeyButton(String label, View.OnClickListener listener, boolean accent) {
        final TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        chip.setTextColor(accent ? COLOR_ACCENT : COLOR_TEXT);
        chip.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(6), dp(5), dp(6), dp(5));
        chip.setClickable(true);
        chip.setFocusable(false);
        chip.setOnClickListener(listener);

        final GradientDrawable background = new GradientDrawable();
        background.setColor(COLOR_PANEL_ALT);
        background.setCornerRadius(dp(8));
        chip.setBackground(background);
        chip.setShadowLayer(dpFloat(2f), 0f, dpFloat(1.5f), 0x55000000);
        chip.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        background.setColor(COLOR_ACTIVE);
                        chip.animate().cancel();
                        chip.animate().translationY(dp(1)).setDuration(70).start();
                        chip.setShadowLayer(dpFloat(4f), 0f, dpFloat(3f), 0x77000000);
                        chip.invalidate();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        background.setColor(COLOR_PANEL_ALT);
                        chip.animate().cancel();
                        chip.animate().translationY(0f).setDuration(90).start();
                        chip.setShadowLayer(dpFloat(2f), 0f, dpFloat(1.5f), 0x55000000);
                        chip.invalidate();
                        break;
                    default:
                        break;
                }
                return false;
            }
        });

        return chip;
    }

    private TextView createSidebarAction(String label, int color, View.OnClickListener listener) {
        TextView action = new TextView(this);
        action.setText(label);
        action.setTextColor(color);
        action.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        action.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(8), dp(6), dp(8), dp(6));
        action.setOnClickListener(listener);

        GradientDrawable background = new GradientDrawable();
        background.setColor(COLOR_PANEL);
        background.setCornerRadius(dp(8));
        background.setStroke(dp(1), COLOR_BORDER);
        action.setBackground(background);
        return action;
    }

    private void addSession(boolean activate) {
        final SessionRecord session = new SessionRecord(
                nextSessionId,
                "s" + nextSessionId,
                new TerminalView(this));
        nextSessionId++;

        session.terminal = new Terminal(this, new Terminal.Listener() {
            @Override
            public void onOutput(final String text) {
                session.terminalView.post(new Runnable() {
                    @Override
                    public void run() {
                        session.terminalView.appendOutput(text);
                    }
                });
            }

            @Override
            public void onExit(final int exitCode) {
                session.exited = true;
                session.terminalView.post(new Runnable() {
                    @Override
                    public void run() {
                        session.terminalView.appendOutput(
                                "\r\n[Process exited with code " + exitCode + "]\r\n");
                    }
                });
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refreshSessionList();
                    }
                });
            }
        });
        session.terminalView.setTerminal(session.terminal);

        sessions.add(session);
        refreshSessionList();

        if (activate || activeSession == null) {
            switchToSession(session);
        }

        session.terminal.start();
    }

    private void removeSession(SessionRecord session) {
        int index = sessions.indexOf(session);
        if (index < 0) {
            return;
        }

        sessions.remove(index);
        if (session.terminal != null) {
            session.terminal.destroy();
        }

        boolean removedActive = session == activeSession;
        if (removedActive) {
            activeSession = null;
            terminalPane.removeAllViews();
        }

        if (sessions.isEmpty()) {
            addSession(true);
            return;
        }

        if (removedActive) {
            int nextIndex = Math.min(index, sessions.size() - 1);
            switchToSession(sessions.get(nextIndex));
        } else {
            refreshSessionList();
        }
    }

    private void switchToSession(SessionRecord session) {
        if (session == null) {
            return;
        }

        activeSession = session;
        terminalPane.removeAllViews();

        ViewParent parent = (ViewParent) session.terminalView.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(session.terminalView);
        }

        terminalPane.addView(session.terminalView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        session.terminalView.requestFocus();
        refreshSessionList();
    }

    private void refreshSessionList() {
        if (sessionList == null) {
            return;
        }

        sessionList.removeAllViews();
        for (final SessionRecord session : sessions) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(8);
            sessionList.addView(createSessionRow(session), params);
        }
        updateSessionHeader();
    }

    private View createSessionRow(final SessionRecord session) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchToSession(session);
                setSidebarVisible(false);
            }
        });

        GradientDrawable background = new GradientDrawable();
        background.setColor(session == activeSession ? COLOR_ACTIVE : COLOR_PANEL);
        background.setCornerRadius(dp(10));
        background.setStroke(dp(1), session == activeSession ? COLOR_ACCENT : COLOR_BORDER);
        row.setBackground(background);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(this);
        name.setText(session.name);
        name.setTextColor(COLOR_TEXT);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        name.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);

        TextView meta = new TextView(this);
        meta.setText(session.exited ? "stopped" : "ubuntu");
        meta.setTextColor(session.exited ? COLOR_DANGER : COLOR_MUTED);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        meta.setTypeface(Typeface.MONOSPACE);

        info.addView(name);
        info.addView(meta);

        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoParams.weight = 1f;
        row.addView(info, infoParams);

        row.addView(createSidebarAction("x", COLOR_DANGER, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                removeSession(session);
            }
        }));

        return row;
    }

    private void updateSessionHeader() {
        // No top status bar; session state is reflected in the sidebar only.
    }

    private void setSidebarVisible(boolean visible) {
        sidebarVisible = visible;
        if (sidebarPane != null) {
            sidebarPane.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void sendActiveInput(final String text) {
        withActiveTerminal(new TerminalAction() {
            @Override
            public void run(TerminalView view) {
                view.sendInput(text);
            }
        });
    }

    private void sendActiveControlKey(final char key) {
        withActiveTerminal(new TerminalAction() {
            @Override
            public void run(TerminalView view) {
                view.sendControlKey(key);
            }
        });
    }

    private void withActiveTerminal(TerminalAction action) {
        if (activeSession == null || activeSession.terminalView == null) {
            return;
        }
        activeSession.terminalView.requestFocus();
        action.run(activeSession.terminalView);
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(COLOR_PANEL);
        window.setNavigationBarColor(COLOR_PANEL);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private float dpFloat(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private interface TerminalAction {
        void run(TerminalView view);
    }
}
