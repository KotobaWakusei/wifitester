package me.paranoid.wifikill;

import org.junit.Test;
import static org.junit.Assert.*;

public class AttackCatalogTest {

    @Test public void exposesEveryModuleIdExactlyOnce() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (AttackCatalog.Module module : AttackCatalog.modules()) {
            assertTrue("duplicate module id: " + module.id, ids.add(module.id));
        }
        assertTrue(ids.contains(AttackCatalog.ID_DEAUTH));
        assertTrue(ids.contains(AttackCatalog.ID_BEACON));
        assertTrue(ids.contains(AttackCatalog.ID_AUTH));
        assertTrue(ids.contains(AttackCatalog.ID_HANDSHAKE));
        assertTrue(ids.contains(AttackCatalog.ID_CRACK));
        assertTrue(ids.contains(AttackCatalog.ID_EVIL_TWIN));
        assertTrue(ids.contains(AttackCatalog.ID_ARP_SPOOF));
    }

    @Test public void onlyTheOfflineModuleIsClassifiedAsOffline() {
        for (AttackCatalog.Module module : AttackCatalog.modules()) {
            boolean offline = module.risk == AttackCatalog.Risk.OFFLINE;
            assertEquals(module.id, offline,
                    module.id.equals(AttackCatalog.ID_CRACK));
            if (offline) {
                assertFalse(module.id + " must not require a monitor interface",
                        module.requiresMonitor);
            }
        }
    }

    @Test public void everyModuleDocumentsItsRiskAndTool() {
        for (AttackCatalog.Module module : AttackCatalog.modules()) {
            assertTrue("the module needs a title resource", module.titleRes != 0);
            assertTrue("the module needs a description resource", module.descriptionRes != 0);
            assertNotNull(module.risk);
            assertTrue("the risk needs a label resource", module.risk.labelRes != 0);
            assertFalse(module.tool.trim().isEmpty());
        }
    }

    @Test public void lookupByIdFindsModulesAndRejectsUnknownIds() {
        assertNotNull(AttackCatalog.byId(AttackCatalog.ID_DEAUTH));
        assertNull(AttackCatalog.byId("does-not-exist"));
        assertNull(AttackCatalog.byId(null));
    }

    @Test public void disruptingModulesCarryTheHighestSeverity() {
        for (AttackCatalog.Module module : AttackCatalog.modules()) {
            if (module.id.equals(AttackCatalog.ID_DEAUTH)
                    || module.id.equals(AttackCatalog.ID_BEACON)
                    || module.id.equals(AttackCatalog.ID_AUTH)) {
                assertEquals(module.id, AttackCatalog.Risk.DISRUPTIVE, module.risk);
            }
        }
    }
}
