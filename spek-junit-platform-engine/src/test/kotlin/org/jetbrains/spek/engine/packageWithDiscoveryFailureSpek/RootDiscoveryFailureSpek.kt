package org.jetbrains.spek.engine.packageWithDiscoveryFailureSpek

import org.jetbrains.spek.api.Spek

/**
 * @author Ashish Pandey
 */
class RootDiscoveryFailureSpek : Spek({
    throw RuntimeException("This always fails")
})