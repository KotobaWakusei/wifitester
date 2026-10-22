package me.paranoid.wifikill;

import java.util.ArrayList;
import java.util.List;

/**
 * Metadata for the active test modules offered by the attack workbench.
 *
 * <p>The catalog describes what each module does, which prerequisites it has
 * and how risky it is, so the UI can present it honestly instead of exposing
 * a one-click button that silently fails.</p>
 *
 * <p>All labels are stored as resource identifiers so the workbench follows the
 * interface language of the device.</p>
 */
final class AttackCatalog {

    /** Risk classification shown next to every module. */
    enum Risk {
        /** Only reads local files; no radio activity. */
        OFFLINE(R.string.attack_risk_offline, 0),
        /** Receives frames; does not transmit. */
        PASSIVE(R.string.attack_risk_passive, 1),
        /** Transmits frames that disrupt other devices. */
        DISRUPTIVE(R.string.attack_risk_disruptive_label, 2),
        /** Impersonates an access point or hijacks traffic. */
        IMPERSONATION(R.string.attack_risk_impersonation, 3);

        final int labelRes;
        final int severity;

        Risk(int labelRes, int severity) {
            this.labelRes = labelRes;
            this.severity = severity;
        }
    }

    /** Prerequisites a module needs before it can run. */
    enum Requirement {
        /** Needs a monitor-mode interface created by the monitor workbench. */
        MONITOR_INTERFACE(R.string.attack_requirement_monitor),
        /** Needs a dictionary file for offline verification. */
        WORDLIST(R.string.attack_requirement_wordlist),
        /** Needs a gateway address in the same subnet. */
        GATEWAY(R.string.attack_requirement_gateway);

        final int labelRes;

        Requirement(int labelRes) {
            this.labelRes = labelRes;
        }
    }

    static final String ID_DEAUTH = "deauth";
    static final String ID_BEACON = "beacon";
    static final String ID_AUTH = "auth";
    static final String ID_HANDSHAKE = "handshake";
    static final String ID_CRACK = "crack";
    static final String ID_EVIL_TWIN = "evil_twin";
    static final String ID_ARP_SPOOF = "arp_spoof";

    private AttackCatalog() {
    }

    static List<Module> modules() {
        ArrayList<Module> modules = new ArrayList<>();
        modules.add(new Module(ID_DEAUTH, R.string.attack_module_deauth,
                R.string.attack_module_deauth_detail,
                Risk.DISRUPTIVE, "aireplay-ng", true));
        modules.add(new Module(ID_BEACON, R.string.attack_module_beacon,
                R.string.attack_module_beacon_detail,
                Risk.DISRUPTIVE, "aireplay-ng", true));
        modules.add(new Module(ID_AUTH, R.string.attack_module_auth,
                R.string.attack_module_auth_detail,
                Risk.DISRUPTIVE, "aireplay-ng", true));
        modules.add(new Module(ID_HANDSHAKE, R.string.attack_module_handshake,
                R.string.attack_module_handshake_detail,
                Risk.PASSIVE, "airodump-ng", true));
        modules.add(new Module(ID_CRACK, R.string.attack_module_crack,
                R.string.attack_module_crack_detail,
                Risk.OFFLINE, "aircrack-ng", false));
        modules.add(new Module(ID_EVIL_TWIN, R.string.attack_module_evil_twin,
                R.string.attack_module_evil_twin_detail,
                Risk.IMPERSONATION, "hostapd", true));
        modules.add(new Module(ID_ARP_SPOOF, R.string.attack_module_arp_spoof,
                R.string.attack_module_arp_spoof_detail,
                Risk.IMPERSONATION, "arpspoof", false));
        return modules;
    }

    static Module byId(String id) {
        if (id == null) {
            return null;
        }
        for (Module module : modules()) {
            if (module.id.equals(id)) {
                return module;
            }
        }
        return null;
    }

    /** One entry of the catalog. */
    static final class Module {
        final String id;
        final int titleRes;
        final int descriptionRes;
        final Risk risk;
        final String tool;
        final boolean requiresMonitor;

        Module(String id, int titleRes, int descriptionRes, Risk risk, String tool,
               boolean requiresMonitor) {
            this.id = id;
            this.titleRes = titleRes;
            this.descriptionRes = descriptionRes;
            this.risk = risk;
            this.tool = tool;
            this.requiresMonitor = requiresMonitor;
        }

        boolean isActive() {
            return risk != Risk.OFFLINE;
        }
    }
}
