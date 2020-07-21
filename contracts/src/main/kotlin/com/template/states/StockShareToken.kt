package com.template.states

import com.r3.corda.lib.tokens.contracts.states.EvolvableTokenType
import com.template.contracts.StockShareTokenContract
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.Party

@BelongsToContract(StockShareTokenContract::class)
data class StockShareToken(
        val company : String,
        val companyCode: String,
        val maintainer: Party,
        val price : Double,
        override val linearId: UniqueIdentifier,
        override val fractionDigits: Int = 0
) : EvolvableTokenType() {

    companion object {
        val contractId = this::class.java.enclosingClass.canonicalName
    }

    override val maintainers: List<Party> get() = listOf(maintainer)
}