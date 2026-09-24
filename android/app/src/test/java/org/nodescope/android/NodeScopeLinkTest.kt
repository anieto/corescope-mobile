package org.nodescope.android

import org.junit.Assert.*
import org.junit.Test
import org.nodescope.android.core.model.NodeScopeLink
import org.nodescope.android.core.model.NodeScopeLink.Kind

class NodeScopeLinkTest {
    @Test fun parsesEveryKindLikeIos() {
        assertEquals(NodeScopeLink(Kind.NODE, "abcd1234"), NodeScopeLink.parse("nodescope://node/abcd1234"))
        assertEquals(NodeScopeLink(Kind.OBSERVER, "42E7CB5A"), NodeScopeLink.parse("NodeScope://OBSERVER/42E7CB5A"))
        assertEquals(NodeScopeLink(Kind.CHANNEL, "#testing"), NodeScopeLink.parse("nodescope://channel/%23testing"))
        assertEquals(NodeScopeLink(Kind.PACKET, "d2be448ac1efbf40"), NodeScopeLink.parse(" nodescope://packet/d2be448ac1efbf40 "))
    }

    @Test fun keepsReservedCharactersAndMultipleSegments() {
        assertEquals("#testing", NodeScopeLink.parse("nodescope://channel/#testing")?.identifier) // unencoded, arrives as a fragment
        assertEquals("user:#ops team", NodeScopeLink.parse("nodescope://channel/user:%23ops%20team")?.identifier)
        assertEquals("a/b", NodeScopeLink.parse("nodescope://channel/a/b")?.identifier)
        assertEquals("a+b", NodeScopeLink.parse("nodescope://channel/a+b")?.identifier) // '+' is literal in a path
    }

    @Test fun rejectsOtherLinks() {
        listOf(null, "", "https://node/abc", "nodescope://map/abc", "nodescope://node/", "nodescope://node", "nodescope://node/%20", "not a url %%")
            .forEach { assertNull(it, NodeScopeLink.parse(it)) }
    }

    @Test fun builtLinksRoundTrip() {
        val links = listOf(NodeScopeLink.node("abcd"), NodeScopeLink.observer("42E7"), NodeScopeLink.channel("#test"),
            NodeScopeLink.channel("user:#ops team"), NodeScopeLink.packet("d2be"))
        assertEquals("nodescope://channel/%23test", NodeScopeLink.channel("#test").url)
        links.forEach { assertEquals(it, NodeScopeLink.parse(it.url)) }
    }
}
