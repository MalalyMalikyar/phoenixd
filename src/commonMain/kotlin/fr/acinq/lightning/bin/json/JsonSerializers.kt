@file:UseSerializers(
    // This is used by Kotlin at compile time to resolve serializers (defined in this file)
    // in order to build serializers for other classes (also defined in this file).
    // If we used @Serializable annotations directly on the actual classes, Kotlin would be
    // able to resolve serializers by itself. It is verbose, but it allows us to contain
    // serialization code in this file.
    JsonSerializers.SatoshiSerializer::class,
    JsonSerializers.MilliSatoshiSerializer::class,
    JsonSerializers.ByteVector32Serializer::class,
    JsonSerializers.PublicKeySerializer::class,
    JsonSerializers.TxIdSerializer::class,
    JsonSerializers.UUIDSerializer::class
)

package fr.acinq.lightning.bin.json

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.bin.db.PaymentMetadata
import fr.acinq.lightning.bin.payments.lnurl.models.Lnurl
import fr.acinq.lightning.bin.payments.lnurl.models.LnurlWithdraw
import fr.acinq.lightning.channel.states.ChannelState
import fr.acinq.lightning.channel.states.ChannelStateWithCommitments
import fr.acinq.lightning.db.*
import fr.acinq.lightning.json.JsonSerializers
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.utils.UUID
import io.ktor.http.*
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlin.math.ln

sealed class ApiType {

    @Serializable
    data class Channel internal constructor(
        val state: String,
        val channelId: ByteVector32? = null,
        val balanceSat: Satoshi? = null,
        val inboundLiquiditySat: Satoshi? = null,
        val capacitySat: Satoshi? = null,
        val fundingTxId: TxId? = null
    ) {
        companion object {
            fun from(channel: ChannelState) = when {
                channel is ChannelStateWithCommitments -> Channel(
                    state = channel.stateName,
                    channelId = channel.channelId,
                    balanceSat = channel.commitments.availableBalanceForSend().truncateToSatoshi(),
                    inboundLiquiditySat = channel.commitments.availableBalanceForReceive().truncateToSatoshi(),
                    capacitySat = channel.commitments.active.first().fundingAmount,
                    fundingTxId = channel.commitments.active.first().fundingTxId
                )
                else -> Channel(state = channel.stateName)
            }
        }
    }

    @Serializable
    data class NodeInfo(
        val nodeId: PublicKey,
        val channels: List<Channel>,
        val chain: String,
        val blockHeight: Int?,
        val version: String
    )

    @Serializable
    data class Balance(@SerialName("balanceSat") val amount: Satoshi, @SerialName("feeCreditSat") val feeCredit: Satoshi) : ApiType()

    @Serializable
    data class GeneratedInvoice(@SerialName("amountSat") val amount: Satoshi?, val paymentHash: ByteVector32, val serialized: String) : ApiType()

    @Serializable
    sealed class ApiEvent : ApiType() {
        val timestamp: Long = Clock.System.now().toEpochMilliseconds()
    }

    @Serializable
    @SerialName("payment_received")
    data class PaymentReceived(@SerialName("amountSat") val amount: Satoshi, val paymentHash: ByteVector32, val externalId: String?) : ApiEvent() {
        constructor(event: fr.acinq.lightning.PaymentEvents.PaymentReceived, metadata: PaymentMetadata?) : this(event.amount.truncateToSatoshi(), event.paymentHash, metadata?.externalId)
    }

    @Serializable
    @SerialName("payment_sent")
    data class PaymentSent(@SerialName("recipientAmountSat") val recipientAmount: Satoshi, @SerialName("routingFeeSat") val routingFee: Satoshi, @SerialName("paymentId") val uuid: UUID, val paymentHash: ByteVector32, val paymentPreimage: ByteVector32) : ApiType() {
        constructor(event: fr.acinq.lightning.io.PaymentSent) : this(
            event.payment.recipientAmount.truncateToSatoshi(),
            event.payment.routingFee.truncateToSatoshi(),
            event.request.paymentId,
            event.payment.paymentHash,
            (event.payment.status as LightningOutgoingPayment.Status.Completed.Succeeded.OffChain).preimage
        )
    }

    @Serializable
    @SerialName("payment_failed")
    data class PaymentFailed(val paymentHash: ByteVector32?, val offerId: ByteVector32?, val reason: String) : ApiType() {
        constructor(event: fr.acinq.lightning.io.PaymentNotSent) : this(paymentHash = event.request.paymentHash, offerId = null, reason = event.reason.explain().fold({ it.toString() }, { it.toString() }))
        constructor(event: fr.acinq.lightning.io.OfferNotPaid) : this(paymentHash = null, offerId = event.request.offer.offerId, event.reason.toString())
    }

    @Serializable
    @SerialName("incoming_payment")
    data class IncomingPayment(val paymentHash: ByteVector32, val preimage: ByteVector32, val externalId: String?, val description: String?, val invoice: String?, val isPaid: Boolean, val receivedSat: Satoshi, val fees: MilliSatoshi, val completedAt: Long?, val createdAt: Long) {
        constructor(payment: fr.acinq.lightning.db.IncomingPayment, externalId: String?) : this (
            paymentHash = payment.paymentHash,
            preimage = payment.preimage,
            externalId = externalId,
            description = (payment.origin as? fr.acinq.lightning.db.IncomingPayment.Origin.Invoice)?.paymentRequest?.description,
            invoice = (payment.origin as? fr.acinq.lightning.db.IncomingPayment.Origin.Invoice)?.paymentRequest?.write(),
            isPaid = payment.completedAt != null,
            receivedSat = payment.amount.truncateToSatoshi(),
            fees = payment.fees,
            completedAt = payment.completedAt,
            createdAt = payment.createdAt,
        )
    }

    @Serializable
    @SerialName("outgoing_payment")
    data class OutgoingPayment(val paymentId: String, val paymentHash: ByteVector32, val preimage: ByteVector32?, val isPaid: Boolean, val sent: Satoshi, val fees: MilliSatoshi, val invoice: String?, val completedAt: Long?, val createdAt: Long) {
        constructor(payment: LightningOutgoingPayment) : this(
            paymentId = payment.id.toString(),
            paymentHash = payment.paymentHash,
            preimage = (payment.status as? LightningOutgoingPayment.Status.Completed.Succeeded.OffChain)?.preimage,
            invoice = (payment.details as? LightningOutgoingPayment.Details.Normal)?.paymentRequest?.write(),
            isPaid = payment.status is LightningOutgoingPayment.Status.Completed.Succeeded.OffChain,
            sent = payment.amount.truncateToSatoshi(),
            fees = payment.fees,
            completedAt = payment.completedAt,
            createdAt = payment.createdAt,
        )
    }

    @Serializable
    @SerialName("lnurl_request")
    data class LnurlRequest(val url: String, val tag: String?) {
        constructor(lnurl: Lnurl) : this(
            url = lnurl.initialUrl.toString(),
            tag = if (lnurl is Lnurl.Request) lnurl.tag?.label else null,
        )
    }

    @Serializable
    @SerialName("lnurl_withdraw")
    data class LnurlWithdrawResponse(val url: String, val minWithdrawable: MilliSatoshi, val maxWithdrawable: MilliSatoshi, val description: String, val k1: String, val invoice: String) {
        constructor(lnurl: LnurlWithdraw, invoice: Bolt11Invoice) : this(
            url = lnurl.initialUrl.toString(),
            minWithdrawable = lnurl.minWithdrawable,
            maxWithdrawable = lnurl.maxWithdrawable,
            description = lnurl.defaultDescription,
            k1 = lnurl.k1,
            invoice = invoice.write()
        )
    }
}
