package IpmiToolGui;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.plaf.FontUIResource;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;


/**
 * Java 6 compatible Swing front-end for ipmitool.
 * Windows: ipmitool.exe   Linux: ./ipmitool
 *
 * Compile: javac -encoding UTF-8 -source 1.6 -target 1.6 IpmiToolGui.java
 * Run:     java IpmiToolGui
 */

@SuppressWarnings({ "rawtypes", "unchecked" })
public class IpmiToolGui extends JFrame {

    private static final long serialVersionUID = 1L;

    /** 全程式字體大小，想再大小調整改這裡即可 */
    private static final int FONT_SIZE = 16;

    // ===== 預留給你放「開機 / 關機 / 未知」狀態圖片 =====
    // 建議圖片大小約 40x40。例如：
    //   ICON_ON  = new javax.swing.ImageIcon("on.png");
    //   ICON_OFF = new javax.swing.ImageIcon("off.png");
    // 沒設定(null)時，群組清單第一欄會保持空白，但空間已預留。
    private static Icon ICON_ON = new javax.swing.ImageIcon(IpmiToolGui.class.getResource("/images/on.png"));
    private static Icon ICON_OFF = new javax.swing.ImageIcon(IpmiToolGui.class.getResource("/images/off.png"));
    private static Icon ICON_UNKNOWN = null;
    private static final int ICON_COLUMN_WIDTH = 80;
    private static final int GROUP_ROW_HEIGHT = 52;

    // 群組清單的狀態文字
    private static final String ST_UNCHECKED = "未檢查";
    private static final String ST_CHECKING = "檢查中...";
    private static final String ST_ON = "開機";
    private static final String ST_OFF = "關機";
    private static final String ST_FAIL = "無法連線";

    // 按鈕名稱, ipmitool 參數, 是否為危險操作(需確認)
    private static final Object[][] PRESETS = new Object[][] {
        { "電源狀態",       "chassis power status", Boolean.FALSE },
        { "開機",           "chassis power on",     Boolean.TRUE  },
        { "關機(立即斷電)", "chassis power off",    Boolean.TRUE  },
        { "正常關機(ACPI)", "chassis power soft",   Boolean.TRUE  },
        { "斷電再開機",     "chassis power cycle",  Boolean.TRUE  },
        { "硬重置",         "chassis power reset",  Boolean.TRUE  },
        { "機箱狀態",       "chassis status",       Boolean.FALSE },
        { "感測器清單",     "sensor list",          Boolean.FALSE },
        { "SDR 清單",       "sdr list",             Boolean.FALSE },
        { "事件日誌(SEL)",  "sel list",             Boolean.FALSE },
        { "清除事件日誌",   "sel clear",            Boolean.TRUE  },
        { "BMC 資訊",       "mc info",              Boolean.FALSE },
        { "重啟 BMC(冷)",   "mc reset cold",        Boolean.TRUE  },
        { "重啟 BMC(暖)",   "mc reset warm",        Boolean.TRUE  },
        { "FRU 資訊",       "fru print",            Boolean.FALSE },
        { "網路設定",       "lan print 1",          Boolean.FALSE },
        { "使用者清單",     "user list 1",          Boolean.FALSE },
    };

    // ----- 共用連線設定 -----
    private final JTextField pathField = new JTextField(isWindows() ? "ipmitool.exe" : "./ipmitool");
    private final JComboBox ifaceBox = new JComboBox(new String[] { "lanplus", "lan", "open" });
    private final JComboBox userBox = new JComboBox(new String[] { "admin","ADMIN", "root" });
    private final JComboBox passBox = new JComboBox(new String[] {"abc123..","wscloudx!@#852", "ADMIN", "admin", "root",  "calvin", "cus@55cloudx" });

    // ----- 單台控制頁 -----
    private final JTextField hostField = new JTextField("192.168.0.100");
    private final JTextField customField = new JTextField();
    private final JTextArea output = new JTextArea();
    private final JButton runBtn = new JButton("執行");
    private final JButton cancelBtn = new JButton("取消");
    private final List<JButton> presetButtons = new ArrayList<JButton>();
    private volatile Process current;

    // ----- 群組狀態頁 -----
    private final StatusModel statusModel = new StatusModel();
    private final JTable statusTable = new JTable(statusModel);
    private final JTextField ipInput = new JTextField();
    private final ExecutorService pool = Executors.newFixedThreadPool(8);

    public IpmiToolGui() {
        super("IPMI 工具");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        userBox.setEditable(true);
        passBox.setEditable(true);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("單台控制", buildSingleTab());
        tabs.addTab("群組狀態", buildGroupTab());

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(buildConnectionPanel(), BorderLayout.NORTH);
        root.add(tabs, BorderLayout.CENTER);
        setContentPane(root);

        setSize(1280, 900);
        setLocationRelativeTo(null);
    }

    // =====================================================================
    // 共用連線設定
    // =====================================================================
    private JPanel buildConnectionPanel() {
        JPanel conn = new JPanel(new GridLayout(2, 4, 8, 8));
        conn.setBorder(BorderFactory.createTitledBorder("連線設定(兩頁共用)"));
        conn.add(new JLabel("ipmitool 路徑"));
        conn.add(pathField);
        conn.add(new JLabel("介面 (-I)"));
        conn.add(ifaceBox);
        conn.add(new JLabel("帳號 (-U)"));
        conn.add(userBox);
        conn.add(new JLabel("密碼"));
        conn.add(passBox);
        return conn;
    }

    // =====================================================================
    // 第 1 頁：單台控制
    // =====================================================================
    private JPanel buildSingleTab() {
        JPanel hostPanel = new JPanel(new BorderLayout(8, 8));
        hostPanel.setBorder(BorderFactory.createTitledBorder("目標主機 (open=本機時忽略)"));
        hostPanel.add(new JLabel("主機 IP (-H)"), BorderLayout.WEST);
        hostPanel.add(hostField, BorderLayout.CENTER);

        JPanel presets = new JPanel(new GridLayout(0, 4, 8, 8));
        presets.setBorder(BorderFactory.createTitledBorder("常用指令"));
        for (int i = 0; i < PRESETS.length; i++) {
            final String label = (String) PRESETS[i][0];
            final String args = (String) PRESETS[i][1];
            final boolean danger = ((Boolean) PRESETS[i][2]).booleanValue();
            JButton b = new JButton(label);
            b.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    if (danger) {
                        int r = JOptionPane.showConfirmDialog(IpmiToolGui.this,
                                "確定要對 " + hostField.getText() + " 執行「" + label + "」(" + args + ") 嗎?",
                                "確認", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                        if (r != JOptionPane.YES_OPTION) {
                            return;
                        }
                    }
                    execute(args);
                }
            });
            presetButtons.add(b);
            presets.add(b);
        }

        JPanel custom = new JPanel(new BorderLayout(8, 8));
        custom.setBorder(BorderFactory.createTitledBorder("自訂參數(接在連線選項之後)"));
        custom.add(customField, BorderLayout.CENTER);
        JPanel btns = new JPanel(new GridLayout(1, 2, 8, 8));
        btns.add(runBtn);
        btns.add(cancelBtn);
        custom.add(btns, BorderLayout.EAST);
        cancelBtn.setEnabled(false);

        ActionListener runCustom = new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                String s = customField.getText().trim();
                if (s.length() > 0) {
                    execute(s);
                }
            }
        };
        runBtn.addActionListener(runCustom);
        customField.addActionListener(runCustom);
        cancelBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                Process p = current;
                if (p != null) {
                    p.destroy();
                }
            }
        });

        JPanel north = new JPanel(new BorderLayout(8, 8));
        north.add(hostPanel, BorderLayout.NORTH);
        north.add(presets, BorderLayout.CENTER);
        north.add(custom, BorderLayout.SOUTH);

        output.setEditable(false);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, FONT_SIZE));

        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        p.add(north, BorderLayout.NORTH);
        p.add(new JScrollPane(output), BorderLayout.CENTER);
        return p;
    }

    // =====================================================================
    // 第 2 頁：群組狀態
    // =====================================================================
    private JPanel buildGroupTab() {
        // 加入 IP 的輸入列
        JPanel addRow = new JPanel(new BorderLayout(8, 8));
        addRow.setBorder(BorderFactory.createTitledBorder("新增 IP(可一次貼上多個，以空白、逗號、分號或換行分隔)"));
        addRow.add(ipInput, BorderLayout.CENTER);
        JButton addBtn = new JButton("新增");
        addRow.add(addBtn, BorderLayout.EAST);

        ActionListener doAdd = new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                addIps(ipInput.getText());
                ipInput.setText("");
            }
        };
        addBtn.addActionListener(doAdd);
        ipInput.addActionListener(doAdd);

        // 操作按鈕列
        JButton checkAll = new JButton("檢查全部");
        JButton checkSel = new JButton("檢查選取");
        JButton removeSel = new JButton("刪除選取");
        JButton clearAll = new JButton("清空清單");
        checkAll.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                int n = statusModel.getRowCount();
                int[] all = new int[n];
                for (int i = 0; i < n; i++) {
                    all[i] = i;
                }
                checkRows(all);
            }
        });
        checkSel.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                checkRows(statusTable.getSelectedRows());
            }
        });
        removeSel.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                int[] sel = statusTable.getSelectedRows();
                for (int i = sel.length - 1; i >= 0; i--) {
                    statusModel.rows.remove(sel[i]);
                }
                statusModel.fireTableDataChanged();
            }
        });
        clearAll.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                statusModel.rows.clear();
                statusModel.fireTableDataChanged();
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        buttons.add(checkAll);
        buttons.add(checkSel);
        buttons.add(removeSel);
        buttons.add(clearAll);

        // 表格
        statusTable.setRowHeight(GROUP_ROW_HEIGHT);
        statusTable.setFillsViewportHeight(true);
        TableColumn iconCol = statusTable.getColumnModel().getColumn(0);
        iconCol.setMinWidth(ICON_COLUMN_WIDTH);
        iconCol.setMaxWidth(ICON_COLUMN_WIDTH);
        iconCol.setPreferredWidth(ICON_COLUMN_WIDTH);
        statusTable.getColumnModel().getColumn(1).setPreferredWidth(220);
        statusTable.getColumnModel().getColumn(2).setPreferredWidth(140);
        statusTable.getColumnModel().getColumn(3).setPreferredWidth(480);
        statusTable.getColumnModel().getColumn(2).setCellRenderer(new StateRenderer());

        JPanel north = new JPanel(new BorderLayout(8, 8));
        north.add(addRow, BorderLayout.NORTH);
        north.add(buttons, BorderLayout.SOUTH);

        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        p.add(north, BorderLayout.NORTH);
        p.add(new JScrollPane(statusTable), BorderLayout.CENTER);
        return p;
    }

    private void addIps(String text) {
        String[] parts = text.trim().split("[\\s,;]+");
        for (int i = 0; i < parts.length; i++) {
            String ip = parts[i].trim();
            if (ip.length() == 0 || containsIp(ip)) {
                continue;
            }
            statusModel.rows.add(new Row(ip));
        }
        statusModel.fireTableDataChanged();
    }

    private boolean containsIp(String ip) {
        for (int i = 0; i < statusModel.rows.size(); i++) {
            if (statusModel.rows.get(i).ip.equals(ip)) {
                return true;
            }
        }
        return false;
    }

    /** 對指定列平行檢查電源狀態 */
    private void checkRows(int[] indices) {
        if (indices.length == 0) {
            return;
        }
        final String iface = (String) ifaceBox.getSelectedItem();
        if ("open".equals(iface)) {
            JOptionPane.showMessageDialog(this, "群組檢查需要網路介面，請在連線設定選 lanplus 或 lan。");
            return;
        }
        final String path = pathField.getText().trim();
        final String user = comboText(userBox).trim();
        final String password = new String(comboText(passBox));

        for (int i = 0; i < indices.length; i++) {
            final Row row = statusModel.rows.get(indices[i]);
            row.state = ST_CHECKING;
            row.msg = "";
            statusModel.fireTableRowsUpdated(indices[i], indices[i]);
            pool.execute(new Runnable() {
                public void run() {
                    final String[] res = queryPower(path, iface, user, password, row.ip);
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            row.state = res[0];
                            row.msg = res[1];
                            int idx = statusModel.rows.indexOf(row);
                            if (idx >= 0) {
                                statusModel.fireTableRowsUpdated(idx, idx);
                            }
                        }
                    });
                }
            });
        }
    }

    /** 回傳 {狀態, 訊息}。-N 3 -R 1 = 逾時 3 秒、重試 1 次，避免連不上時卡很久 */
    private static String[] queryPower(String path, String iface, String user, String password, String ip) {
        List<String> cmd = new ArrayList<String>();
        cmd.add(path);
        cmd.add("-I");
        cmd.add(iface);
        cmd.add("-H");
        cmd.add(ip);
        cmd.add("-U");
        cmd.add(user);
        cmd.add("-E");
        cmd.add("-N");
        cmd.add("3");
        cmd.add("-R");
        cmd.add("1");
        cmd.add("chassis");
        cmd.add("power");
        cmd.add("status");
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.environment().put("IPMI_PASSWORD", password);
            Process p = pb.start();
            p.getOutputStream().close();
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            } finally {
                r.close();
            }
            p.waitFor();
            String text = sb.toString().trim();
            String lower = text.toLowerCase();
            if (lower.indexOf("power is on") >= 0) {
                return new String[] { ST_ON, text };
            }
            if (lower.indexOf("power is off") >= 0) {
                return new String[] { ST_OFF, text };
            }
            int nl = text.indexOf('\n');
            return new String[] { ST_FAIL, nl > 0 ? text.substring(0, nl) : text };
        } catch (Exception ex) {
            return new String[] { ST_FAIL, String.valueOf(ex.getMessage()) };
        }
    }

    private static Icon iconFor(String state) {
        if (ST_ON.equals(state)) {
            return ICON_ON;
        }
        if (ST_OFF.equals(state)) {
            return ICON_OFF;
        }
        return ICON_UNKNOWN;
    }

    private static class Row {
        final String ip;
        String state = ST_UNCHECKED;
        String msg = "";

        Row(String ip) {
            this.ip = ip;
        }
    }

    private static class StatusModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private final String[] cols = { "圖示", "IP", "電源狀態", "訊息" };
        final List<Row> rows = new ArrayList<Row>();

        public int getRowCount() {
            return rows.size();
        }

        public int getColumnCount() {
            return cols.length;
        }

        public String getColumnName(int c) {
            return cols[c];
        }

        public Class<?> getColumnClass(int c) {
            return c == 0 ? Icon.class : String.class;
        }

        public Object getValueAt(int r, int c) {
            Row row = rows.get(r);
            switch (c) {
            case 0:
                return iconFor(row.state);
            case 1:
                return row.ip;
            case 2:
                return row.state;
            default:
                return row.msg;
            }
        }
    }

    /** 狀態欄：置中並依狀態上色 */
    private static class StateRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                boolean focus, int row, int col) {
            Component c = super.getTableCellRendererComponent(t, v, sel, focus, row, col);
            setHorizontalAlignment(SwingConstants.CENTER);
            if (!sel) {
                String s = String.valueOf(v);
                if (ST_ON.equals(s)) {
                    setForeground(new Color(0, 140, 0));
                } else if (ST_OFF.equals(s)) {
                    setForeground(Color.RED);
                } else if (ST_FAIL.equals(s)) {
                    setForeground(new Color(200, 120, 0));
                } else {
                    setForeground(Color.BLACK);
                }
            }
            return c;
        }
    }

    // =====================================================================
    // 單台執行
    // =====================================================================
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().indexOf("win") >= 0;
    }

    private void setBusy(boolean busy) {
        for (int i = 0; i < presetButtons.size(); i++) {
            presetButtons.get(i).setEnabled(!busy);
        }
        runBtn.setEnabled(!busy);
        cancelBtn.setEnabled(busy);
    }

    /** ipmitool [-I x -H h -U u -E] <args...> */
    private List<String> buildCommand(String extraArgs) {
        List<String> cmd = new ArrayList<String>();
        cmd.add(pathField.getText().trim());
        String iface = (String) ifaceBox.getSelectedItem();
        cmd.add("-I");
        cmd.add(iface);
        if (!"open".equals(iface)) {
            cmd.add("-H");
            cmd.add(hostField.getText().trim());
            cmd.add("-U");
            cmd.add(comboText(userBox).trim());
            cmd.add("-E"); // 密碼走環境變數 IPMI_PASSWORD，不會出現在行程清單
        }
        String[] parts = extraArgs.trim().split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].length() > 0) {
                cmd.add(parts[i]);
            }
        }
        return cmd;
    }

    private void execute(String extraArgs) {
        final List<String> cmd = buildCommand(extraArgs);
        final String password = new String(comboText(passBox));

        output.append("> " + join(cmd) + "\n");
        setBusy(true);

        new SwingWorker<Integer, String>() {
            @Override
            protected Integer doInBackground() throws Exception {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                pb.environment().put("IPMI_PASSWORD", password);
                Process p = pb.start();
                current = p;
                p.getOutputStream().close();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        publish(line + "\n");
                    }
                } finally {
                    r.close();
                }
                return Integer.valueOf(p.waitFor());
            }

            @Override
            protected void process(List<String> chunks) {
                for (int i = 0; i < chunks.size(); i++) {
                    output.append(chunks.get(i));
                }
                output.setCaretPosition(output.getDocument().getLength());
            }

            @Override
            protected void done() {
                current = null;
                try {
                    output.append("[結束碼 " + get() + "]\n\n");
                } catch (Exception ex) {
                    Throwable t = ex.getCause() != null ? ex.getCause() : ex;
                    output.append("[錯誤] " + t + "\n\n");
                }
                output.setCaretPosition(output.getDocument().getLength());
                setBusy(false);
            }
        }.execute();
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    // =====================================================================
    // 全域字體
    // =====================================================================
    private static void setUIFont(Font f) {
        List<Object> keys = new ArrayList<Object>();
        Enumeration<?> en = javax.swing.UIManager.getDefaults().keys();
        while (en.hasMoreElements()) {
            keys.add(en.nextElement());
        }
        for (int i = 0; i < keys.size(); i++) {
            Object k = keys.get(i);
            if (javax.swing.UIManager.get(k) instanceof FontUIResource) {
                javax.swing.UIManager.put(k, new FontUIResource(f));
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                setUIFont(new Font("Dialog", Font.PLAIN, FONT_SIZE));
                new IpmiToolGui().setVisible(true);
            }
        });
    }
    
    /** 可編輯下拉選單：取得目前選到或手動輸入的文字 */
    private static String comboText(JComboBox box) {
        Object o = box.isEditable() ? box.getEditor().getItem() : box.getSelectedItem();
        return o == null ? "" : o.toString();
    }
}