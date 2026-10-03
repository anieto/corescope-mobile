package org.nodescope.android

import org.junit.Assert.assertEquals
import org.junit.Test
import org.nodescope.android.app.AppIdentity

class AppIdentityTest {
    @Test fun fingerprintsAreShownTheWayKeyConsolesShowThem() {
        assertEquals("96:72:08:37:6D:96:B8:3A:03:92:D5:1E:33:30:9C:E4:AA:8A:F5:5F",
            AppIdentity.fingerprint("967208376D96B83A0392D51E33309CE4AA8AF55F"))
    }
}
