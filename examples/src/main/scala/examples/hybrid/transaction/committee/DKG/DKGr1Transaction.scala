package examples.hybrid.transaction.committee.DKG

import com.google.common.primitives.{Bytes, Ints, Longs}
import examples.commons.{SimpleBoxTransaction, SimpleBoxTransactionCompanion}
import examples.hybrid.TreasuryManager
import examples.hybrid.transaction.{DKGr1TxTypeId, SignedTreasuryTransaction}
import io.circe.Json
import io.circe.syntax._
import scorex.core.ModifierTypeId
import scorex.core.serialization.Serializer
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.transaction.proof.Signature25519
import scorex.core.transaction.state.{PrivateKey25519, PrivateKey25519Companion}
import scorex.crypto.encode.Base58
import scorex.crypto.signatures.{Curve25519, PublicKey, Signature}
import treasury.crypto.keygen.datastructures.round1.{R1Data, R1DataSerializer}

import scala.util.Try

case class DKGr1Transaction( r1Data: R1Data,
                             override val epochID: Long,
                             override val pubKey: PublicKey25519Proposition, // previously registered committee public key
                             override val signature: Signature25519,
                             override val timestamp: Long) extends SignedTreasuryTransaction(timestamp) {

  override type M = SimpleBoxTransaction

  override val transactionTypeId: ModifierTypeId = DKGr1Transaction.TransactionTypeId

  override val serializer = SimpleBoxTransactionCompanion

  override lazy val messageToSign = {
    val superBytes = Bytes.concat(if (newBoxes.nonEmpty) scorex.core.utils.concatBytes(newBoxes.map(_.bytes)) else Array[Byte](),
      scorex.core.utils.concatFixLengthBytes(unlockers.map(_.closedBoxId)),
      Longs.toByteArray(timestamp),
      Longs.toByteArray(fee))

    Bytes.concat(
      pubKey.bytes,
      r1Data.bytes,
      Longs.toByteArray(epochID),
      superBytes)
  }

  override lazy val json: Json = Map("id" -> Base58.encode(id).asJson).asJson //TODO

  override lazy val semanticValidity: Try[Unit] = Try {
    require(timestamp >= 0)
    require(signature.isValid(pubKey, messageToSign))
  }

  override def toString: String = s"DKGr1Transaction (${json.noSpaces})"
}

object DKGr1Transaction {

  val TransactionTypeId: scorex.core.ModifierTypeId = DKGr1TxTypeId

  def create(privKey: PrivateKey25519,
             r1Data: R1Data,
             epochID: Long): Try[DKGr1Transaction] = Try {
    val timestamp = System.currentTimeMillis()
    val fakeSig = Signature25519(Signature @@ Array[Byte]())
    val unsigned = DKGr1Transaction(r1Data, epochID, privKey.publicImage, fakeSig, timestamp)
    val sig = PrivateKey25519Companion.sign(privKey, unsigned.messageToSign)

    DKGr1Transaction(r1Data, epochID, privKey.publicImage, sig, timestamp)
  }
}

object DKGr1TransactionCompanion extends Serializer[DKGr1Transaction] {

  def toBytes(t: DKGr1Transaction): Array[Byte] = {

    Bytes.concat(
      Ints.toByteArray(t.r1Data.size),
      t.r1Data.bytes,
      Longs.toByteArray(t.epochID),
      t.pubKey.bytes,
      t.signature.bytes,
      Longs.toByteArray(t.timestamp)
    )
  }

  def parseBytes(bytes: Array[Byte]): Try[DKGr1Transaction] = Try {

    var offset = 0
    def offsetPlus (i: Int): Int = { offset += i; offset }

    val r1DataSize = Ints.fromByteArray(bytes.slice(offset, offsetPlus(4)))

    val r1Data = R1DataSerializer.parseBytes(bytes.slice(offset, offsetPlus(r1DataSize)), TreasuryManager.cs).get

    val epochID = Longs.fromByteArray(bytes.slice(offset, offsetPlus(8)))

    val pubKey = PublicKey25519Proposition(PublicKey @@ bytes.slice(offset, offsetPlus(Curve25519.KeyLength)))

    val sig = Signature25519(Signature @@ bytes.slice(offset, offsetPlus(Curve25519.SignatureLength)))

    val timestamp = Longs.fromByteArray(bytes.slice(offset, offsetPlus(8)))

    DKGr1Transaction(r1Data, epochID, pubKey, sig, timestamp)
  }
}
