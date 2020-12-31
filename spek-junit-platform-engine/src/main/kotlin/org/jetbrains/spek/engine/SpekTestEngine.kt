package org.jetbrains.spek.engine

import org.jetbrains.spek.api.CreateWith
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.*
import org.jetbrains.spek.api.lifecycle.CachingMode
import org.jetbrains.spek.api.lifecycle.InstanceFactory
import org.jetbrains.spek.api.lifecycle.LifecycleAware
import org.jetbrains.spek.api.lifecycle.LifecycleListener
import org.jetbrains.spek.engine.lifecycle.LifecycleAwareAdapter
import org.jetbrains.spek.engine.lifecycle.LifecycleManager
import org.junit.platform.commons.util.ReflectionUtils
import org.junit.platform.engine.*
import org.junit.platform.engine.discovery.ClassSelector
import org.junit.platform.engine.discovery.ClasspathRootSelector
import org.junit.platform.engine.discovery.PackageSelector
import org.junit.platform.engine.discovery.UniqueIdSelector
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.EngineDescriptor
import org.junit.platform.engine.support.hierarchical.HierarchicalTestEngine
import org.junit.platform.engine.support.hierarchical.Node
import java.lang.reflect.Modifier
import java.util.*
import java.util.function.Consumer
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

/**
 * @author Ranie Jade Ramiso
 */
class SpekTestEngine: HierarchicalTestEngine<SpekExecutionContext>() {

    val defaultInstanceFactory = object: InstanceFactory {
        override fun <T: Spek> create(spek: KClass<T>): T {
            return spek.objectInstance ?: spek.constructors.first { it.parameters.isEmpty() }
                .call()
        }
    }

    override fun discover(discoveryRequest: EngineDiscoveryRequest, uniqueId: UniqueId): TestDescriptor {
        val engineDescriptor = SpekEngineDescriptor(uniqueId)
        resolveSpecs(discoveryRequest, engineDescriptor)
        return engineDescriptor
    }

    override fun getId(): String = "spek"

    override fun createExecutionContext(request: ExecutionRequest)
        = SpekExecutionContext()

    private fun resolveSpecs(discoveryRequest: EngineDiscoveryRequest, engineDescriptor: EngineDescriptor) {
        val isValidSpec = java.util.function.Predicate<Class<*>> {
            Spek::class.java.isAssignableFrom(it) && !Modifier.isAbstract(it.modifiers)
        }

        val isSpecClass = java.util.function.Predicate<String>(String::isNotEmpty)
        discoveryRequest.getSelectorsByType(ClasspathRootSelector::class.java).forEach {
            ReflectionUtils.findAllClassesInClasspathRoot(it.classpathRoot, isValidSpec, isSpecClass)
                .forEach {
                    resolveSpec(engineDescriptor, it)
                }
        }

        discoveryRequest.getSelectorsByType(PackageSelector::class.java).forEach {
            ReflectionUtils.findAllClassesInPackage(it.packageName, isValidSpec, isSpecClass).forEach {
                resolveSpec(engineDescriptor, it)
            }
        }

        discoveryRequest.getSelectorsByType(ClassSelector::class.java).forEach {
            if (isValidSpec.test(it.javaClass)) {
                resolveSpec(engineDescriptor, it.javaClass as Class<Spek>)
            }
        }

        discoveryRequest.getSelectorsByType(UniqueIdSelector::class.java).forEach {
            engineDescriptor.findByUniqueId(it.uniqueId).ifPresent(Consumer {
                filterOutUniqueId(it, engineDescriptor)
            })
        }
    }

    private fun filterOutUniqueId(target: TestDescriptor, root: TestDescriptor) {
        if (target != root) {
            if (root.descendants.contains(target)) {
                val descriptors = LinkedList<TestDescriptor>()
                root.children.forEach {
                    descriptors.add(it)
                }

                descriptors.forEach { filterOutUniqueId(target, it) }
            } else {
                root.removeFromHierarchy()
            }
        }
    }

    private fun resolveSpec(engineDescriptor: EngineDescriptor, klass: Class<*>) {
        val fixtures = FixturesAdapter()
        val lifecycleManager = LifecycleManager().apply {
            addListener(fixtures)
        }

        val kotlinClass = klass.kotlin
        val instance = instanceFactoryFor(kotlinClass).create(kotlinClass as KClass<Spek>)
        val root = Scope.Group(
            engineDescriptor.uniqueId.append(SPEC_SEGMENT_TYPE, klass.name),
            Pending.No,
            ClassSource.from(klass), lifecycleManager
        )
        engineDescriptor.addChild(root)

        val rootCollector = Collector(root, lifecycleManager, fixtures)
        try {
            instance.spec.invoke(rootCollector)
        } catch (e: Throwable) {
            root.addChild(Scope.Test(
                    root.uniqueId.append(TEST_SEGMENT_TYPE, "Discovery failure"),
                    Pending.No, getSource(), lifecycleManager, { throw e }
            ))
        }
    }

    private fun instanceFactoryFor(spek: KClass<*>): InstanceFactory {
        val factory = spek.annotations.filterIsInstance<CreateWith>()
            .map { it.factory }
            .map { it.objectInstance ?: it.primaryConstructor!!.call() }
            .firstOrNull() ?: defaultInstanceFactory
        return factory
    }

    open class Collector(val root: Scope.Group,
                         val lifecycleManager: LifecycleManager,
                         val fixtures: FixturesAdapter): Spec {

        override fun <T> memoized(mode: CachingMode, factory: () -> T): LifecycleAware<T> {
            val adapter = when (mode) {
                CachingMode.GROUP -> LifecycleAwareAdapter.GroupCachingModeAdapter(factory)
                CachingMode.TEST -> LifecycleAwareAdapter.TestCachingModeAdapter(factory)
                CachingMode.SCOPE -> LifecycleAwareAdapter.ScopeCachingModeAdapter(root, factory)
            }
            return adapter.apply {
                registerListener(this)
            }
        }

        override fun registerListener(listener: LifecycleListener) {
            lifecycleManager.addListener(listener)
        }

        override fun group(description: String, pending: Pending, body: SpecBody.() -> Unit) {
            val group = Scope.Group(
                root.uniqueId.append(GROUP_SEGMENT_TYPE, description),
                pending, getSource(), lifecycleManager
            )
            root.addChild(group)
            val collector = Collector(group, lifecycleManager, fixtures)
            try {
                body.invoke(collector)
            } catch (e: Throwable) {
                collector.beforeGroup { throw e }
                group.addChild(Scope.Test(
                    root.uniqueId.append(TEST_SEGMENT_TYPE, "Group failure"),
                    pending, getSource(), lifecycleManager, {}
                ))
            }

        }

        override fun action(description: String, pending: Pending, body: ActionBody.() -> Unit) {
            val action = Scope.Action(
                root.uniqueId.append(GROUP_SEGMENT_TYPE, description),
                pending, getSource(), lifecycleManager
            ) { dynamicTestExecutor ->
                body.invoke(ActionCollector(this, lifecycleManager, dynamicTestExecutor))
            }

            root.addChild(action)
        }

        override fun test(description: String, pending: Pending, body: TestBody.() -> Unit) {
            val test = Scope.Test(
                root.uniqueId.append(TEST_SEGMENT_TYPE, description),
                pending, getSource(), lifecycleManager, body
            )
            root.addChild(test)
        }

        override fun beforeEachTest(callback: () -> Unit) {
            fixtures.registerBeforeEachTest(root, callback)
        }

        override fun afterEachTest(callback: () -> Unit) {
            fixtures.registerAfterEachTest(root, callback)
        }

        override fun beforeGroup(callback: () -> Unit) {
            fixtures.registerBeforeGroup(root, callback)
        }

        override fun afterGroup(callback: () -> Unit) {
            fixtures.registerAfterGroup(root, callback)
        }
    }

    class ActionCollector(val root: Scope.Action, val lifecycleManager: LifecycleManager,
                          val dynamicTestExecutor: Node.DynamicTestExecutor): ActionBody {

        override fun test(description: String, pending: Pending, body: TestBody.() -> Unit) {
            val test = Scope.Test(
                root.uniqueId.append(TEST_SEGMENT_TYPE, description), pending, getSource(), lifecycleManager, body
            )
            root.addChild(test)
            dynamicTestExecutor.execute(test)
        }

    }

    companion object {
        const val SPEC_SEGMENT_TYPE = "spec"
        const val GROUP_SEGMENT_TYPE = "group"
        const val TEST_SEGMENT_TYPE = "test"

        // TODO: fix me
        fun getSource(): TestSource? = null
    }
}
