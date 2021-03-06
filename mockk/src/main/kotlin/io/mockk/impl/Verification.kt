package io.mockk.impl

import io.mockk.*
import io.mockk.MockKGateway.*
import io.mockk.external.logger
import java.lang.AssertionError

internal class VerifierImpl(gateway: MockKGatewayImpl) : CommonRecorder(gateway), Verifier {
    private val log = logger<VerifierImpl>()

    override fun <T> verify(ordering: Ordering, inverse: Boolean,
                            atLeast: Int,
                            atMost: Int,
                            exactly: Int,
                            mockBlock: (MockKVerificationScope.() -> T)?,
                            coMockBlock: (suspend MockKVerificationScope.() -> T)?) {
        if (ordering != Ordering.UNORDERED) {
            if (atLeast != 1 || atMost != Int.MAX_VALUE || exactly != -1) {
                throw MockKException("atLeast, atMost, exactly is only allowed in unordered verify block")
            }
        }

        val callRecorder = gateway.callRecorder
        callRecorder.startVerification()

        val lambda = slot<Function<*>>()
        val scope = MockKVerificationScope(gateway, lambda)

        try {
            record(scope, mockBlock, coMockBlock)
        } catch (ex: NoClassDefFoundError) {
            callRecorder.cancel()
            throw prettifyCoroutinesException(ex)
        } catch (ex: Throwable) {
            callRecorder.cancel()
            throw ex
        }

        try {
            try {
                val min = if (exactly != -1) exactly else atLeast
                val max = if (exactly != -1) exactly else atMost

                val outcome = gateway.verifier(ordering).verify(callRecorder.calls, min, max)

                log.trace { "Done verification. Outcome: $outcome" }

                failIfNotPassed(outcome, inverse)
            } finally {
                callRecorder.doneVerification()
            }
        } catch (ex: Throwable) {
            callRecorder.cancel()
            throw ex
        }
    }

    private fun failIfNotPassed(outcome: VerificationResult, inverse: Boolean) {
        val explanation = if (outcome.message != null) ": ${outcome.message}" else ""

        if (inverse) {
            if (outcome.matches) {
                throw AssertionError("Inverse verification failed$explanation")
            }
        } else {
            if (!outcome.matches) {
                throw AssertionError("Verification failed$explanation")
            }
        }
    }


}

internal class UnorderedCallVerifierImpl(private val gw: MockKGatewayImpl) : CallVerifier {
    override fun verify(calls: List<Call>, min: Int, max: Int): VerificationResult {
        for ((i, call) in calls.withIndex()) {
            val result = matchCall(call, min, max, "call ${i + 1} of ${calls.size}.")

            if (!result.matches) {
                return result
            }
        }
        return VerificationResult(true)
    }

    private fun matchCall(call: Call, min: Int, max: Int, callIdxMsg: String): VerificationResult {
        val mock = call.invocation.self()
        val allCallsForMock = mock.___allRecordedCalls()
        val allCallsForMockMethod = allCallsForMock.filter {
            call.matcher.method == it.method
        }
        val result = when (allCallsForMockMethod.size) {
            0 -> {
                if (allCallsForMock.isEmpty()) {
                    VerificationResult(false, "$callIdxMsg No calls for $mock/${call.matcher.method.toStr()}")
                } else {
                    VerificationResult(false, "$callIdxMsg No calls for $mock/${call.matcher.method.toStr()}.\n" +
                            "Calls to same mock:\n" + formatCalls(allCallsForMock))
                }
            }
            1 -> {
                val onlyCall = allCallsForMockMethod.get(0)
                if (call.matcher.match(onlyCall)) {
                    if (1 in min..max) {
                        VerificationResult(true)
                    } else {
                        VerificationResult(false, "$callIdxMsg One matching call found, but needs at least $min${atMostMsg(max)} calls")
                    }
                } else {
                    VerificationResult(false, "$callIdxMsg Only one matching call to $mock/${call.matcher.method.toStr()} happened, but arguments are not matching:\n" +
                            describeArgumentDifference(call.matcher, onlyCall))
                }
            }
            else -> {
                val n = allCallsForMockMethod.filter { call.matcher.match(it) }.count()
                if (n in min..max) {
                    VerificationResult(true)
                } else {
                    if (n == 0) {
                        VerificationResult(false,
                                "$callIdxMsg No matching calls found.\n" +
                                        "Calls to same method:\n" + formatCalls(allCallsForMockMethod))
                    } else {
                        VerificationResult(false,
                                "$callIdxMsg $n matching calls found, " +
                                        "but needs at least $min${atMostMsg(max)} calls")
                    }
                }
            }
        }
        return result
    }

    private fun atMostMsg(max: Int) = if (max == Integer.MAX_VALUE) "" else " and at most $max"

    private fun describeArgumentDifference(matcher: InvocationMatcher,
                                           invocation: Invocation): String {
        val str = StringBuilder()
        for ((i, arg) in invocation.args.withIndex()) {
            val argMatcher = matcher.args[i]
            val matches = argMatcher.match(arg)
            str.append("[$i]: argument: $arg, matcher: $argMatcher, result: ${if (matches) "+" else "-"}\n")
        }
        return str.toString()
    }
}

private fun formatCalls(calls: List<Invocation>): String {
    return calls.map {
        it.toString()
    }.joinToString("\n")
}

private fun List<Call>.allCalls() =
        this.map { Ref(it.invocation.self) }
                .distinct()
                .map { it.value as MockKInstance }
                .flatMap { it.___allRecordedCalls() }
                .sortedBy { it.timestamp }

private fun reportCalls(calls: List<Call>, allCalls: List<Invocation>): String {
    return "\nMatchers: \n" + calls.map { it.matcher.toString() }.joinToString("\n") +
            "\nCalls: \n" + formatCalls(allCalls)
}

internal class OrderedCallVerifierImpl(private val gw: MockKGatewayImpl) : CallVerifier {
    override fun verify(calls: List<Call>, min: Int, max: Int): VerificationResult {
        val allCalls = calls.allCalls()

        if (calls.size > allCalls.size) {
            return VerificationResult(false, "less calls happened then demanded by order verification sequence. " +
                    reportCalls(calls, allCalls))
        }

        // LCS algorithm
        var prev = Array(calls.size, { 0 })
        var curr = Array(calls.size, { 0 })
        for (call in allCalls) {
            for ((matcherIdx, matcher) in calls.map { it.matcher }.withIndex()) {
                curr[matcherIdx] = if (matcher.match(call)) {
                    if (matcherIdx == 0) 1 else prev[matcherIdx - 1] + 1
                } else {
                    maxOf(prev[matcherIdx], if (matcherIdx == 0) 0 else curr[matcherIdx - 1])
                }
            }
            val swap = curr
            curr = prev
            prev = swap
        }

        // match only if all matchers present
        if (prev.last() == calls.size) {
            return VerificationResult(true)
        } else {
            return VerificationResult(false, "calls are not in verification order" + reportCalls(calls, allCalls))
        }
    }

}

internal class SequenceCallVerifierImpl(private val gw: MockKGatewayImpl) : CallVerifier {
    override fun verify(calls: List<Call>, min: Int, max: Int): VerificationResult {
        val allCalls = calls.allCalls()

        if (allCalls.size != calls.size) {
            return VerificationResult(false, "number of calls happened not matching exact number of verification sequence" + reportCalls(calls, allCalls))
        }

        for ((i, call) in allCalls.withIndex()) {
            if (!calls[i].matcher.match(call)) {
                return VerificationResult(false, "calls are not exactly matching verification sequence" + reportCalls(calls, allCalls))
            }
        }

        return VerificationResult(true)
    }
}

private fun Invocation.self() = self as MockKInstance
