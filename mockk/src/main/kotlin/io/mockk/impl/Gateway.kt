package io.mockk.impl

import io.mockk.*
import io.mockk.MockKGateway.*
import io.mockk.external.logger
import kotlinx.coroutines.experimental.runBlocking


class MockKGatewayImpl : MockKGateway {
    private val mockFactoryTL = threadLocalOf { MockFactoryImpl(this) }
    private val stubberTL = threadLocalOf { StubberImpl(this) }
    private val verifierTL = threadLocalOf { VerifierImpl(this) }
    private val callRecorderTL = threadLocalOf { CallRecorderImpl(this) }
    private val instantiatorTL = threadLocalOf { InstantiatorImpl(this) }
    private val unorderedVerifierTL = threadLocalOf { UnorderedCallVerifierImpl(this) }
    private val orderedVerifierTL = threadLocalOf { OrderedCallVerifierImpl(this) }
    private val sequenceVerifierTL = threadLocalOf { SequenceCallVerifierImpl(this) }

    override val callRecorder: CallRecorder
        get() = callRecorderTL.get()

    override val instantiator: Instantiator
        get() = instantiatorTL.get()

    override val mockFactory: MockFactory
        get() = mockFactoryTL.get()

    override val stubber: Stubber
        get() = stubberTL.get()

    override val verifier: Verifier
        get() = verifierTL.get()

    override fun verifier(ordering: Ordering): CallVerifier =
            when (ordering) {
                Ordering.UNORDERED -> unorderedVerifierTL.get()
                Ordering.ORDERED -> orderedVerifierTL.get()
                Ordering.SEQUENCE -> sequenceVerifierTL.get()
            }


    companion object {
        val N_CALL_ROUNDS = 64

        private val log = logger<MockKGatewayImpl>()

        init {
            log.trace {
                "Starting Java MockK implementation. " +
                        "Java version = ${System.getProperty("java.version")}. " +
                        "Class loader = ${MockKGatewayImpl::class.java.classLoader}. "
            }
        }

        val defaultImplementation = MockKGatewayImpl()
        val defaultImplementationBuilder = { defaultImplementation }

        inline fun <T> useImpl(block: () -> T) : T {
            MockKGateway.implementation = defaultImplementationBuilder
            return block()
        }
    }

    override fun <T> runCoroutine(block: suspend () -> T): T =  runBlocking { block() }
}

