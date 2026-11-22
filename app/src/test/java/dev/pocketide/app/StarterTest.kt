package dev.pocketide.app

import dev.pocketide.app.core.Starter
import org.junit.Test

class StarterTest {
    @Test fun starter() {
        check(Starter.IDEAS.size == 3 && Starter.IDEAS.all { it.prompt.length > 40 })
        check(Starter.steps(false, false).count { it.done } == 0)
        check(Starter.steps(true, false).count { it.done } == 1)
        check(Starter.steps(true, true).all { it.done })
        check(Starter.show(false) && !Starter.show(true))
    }

    /** 1.5.127: a fresh install is on the keyless demo; that is not a connected AI. */
    @Test fun theDemoIsNotAConnectedAi() {
        check(!Starter.connected("demo", false))
        check(!Starter.connected("demo", true))
        check(!Starter.connected("no-such-provider", false))
        check(Starter.connected("cloudflare", true))
        check(!Starter.connected("cloudflare", false))
        check(Starter.connected("llm7", false))   // keyless, and a real model
    }

    /** 1.5.180: a fresh install starts on a free provider that needs no key, says so, and says what that costs. */
    @Test fun aFreshInstallWorksWithNoKey() {
        val p = dev.pocketide.app.core.Providers.byId(Starter.FIRST_PROVIDER)
        check(p.id == Starter.FIRST_PROVIDER && p.keyless && p.kind != dev.pocketide.app.core.ProviderKind.DEMO)
        check(Starter.connected(Starter.FIRST_PROVIDER, false))
        check(dev.pocketide.app.core.Tools.providerSupports(Starter.FIRST_PROVIDER))
        check(Starter.steps(true, false, keyless = true)[0].let { it.done && it.text.contains("no key") })
        check(Starter.keylessNote(Starter.FIRST_PROVIDER, false)!!.contains("logged"))
        check(Starter.keylessNote(Starter.FIRST_PROVIDER, true) == null)     // the person added a key
        check(Starter.keylessNote("cloudflare", true) == null)
        check(Starter.keylessNote("demo", false) == null)
    }
}
