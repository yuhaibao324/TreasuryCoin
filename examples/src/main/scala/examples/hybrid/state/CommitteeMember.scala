package examples.hybrid.state

import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props}
import akka.util.Timeout
import examples.commons.SimpleBoxTransactionMemPool
import examples.hybrid.HybridNodeViewHolder.{CurrentViewWithTreasuryState, GetDataFromCurrentViewWithTreasuryState}
import examples.hybrid.TreasuryManager
import examples.hybrid.TreasuryManager._
import examples.hybrid.history.HybridHistory
import examples.hybrid.state.CommitteeMember.{StateModified, TxInfo}
import examples.hybrid.transaction.committee.DKG._
import examples.hybrid.transaction._
import examples.hybrid.wallet.HWallet
import scorex.core.NodeViewHolder.ReceivableMessages.LocallyGeneratedTransaction
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.transaction.state.PrivateKey25519
import scorex.core.utils.ScorexLogging
import scorex.crypto.encode.Base58
import treasury.crypto.core.{KeyPair, PrivKey, PubKey, SimpleIdentifier}
import treasury.crypto.keygen.datastructures.round1.R1Data
import treasury.crypto.keygen.datastructures.round2.R2Data
import treasury.crypto.keygen.datastructures.round3.R3Data
import treasury.crypto.keygen.datastructures.round4.R4Data
import treasury.crypto.keygen.datastructures.round5_2.SecretKey
import treasury.crypto.keygen.{DistrKeyGen, RoundsData}

import scala.concurrent.Await
import scala.util.{Failure, Success, Try}

class CommitteeMember(viewHolderRef: ActorRef) extends Actor with ScorexLogging {

  type NodeView = CommitteeMember.NodeView

  private val getTransaction: GetDataFromCurrentViewWithTreasuryState[HybridHistory,
    HBoxStoredState,
    HWallet,
    SimpleBoxTransactionMemPool,
    TxInfo] = {

    val f = (view: NodeView) => TxInfo(getTx(view))

    GetDataFromCurrentViewWithTreasuryState[HybridHistory,
      HBoxStoredState,
      HWallet,
      SimpleBoxTransactionMemPool,
      TxInfo](f)
  }

  override def receive: Receive = {
    case ti: TxInfo =>
      ti.tx match {
        case Some(tx) =>
          log.info(s"Generated tx ${tx.getClass.getName}")
          viewHolderRef ! LocallyGeneratedTransaction[PublicKey25519Proposition, TreasuryTransaction](tx)

        case None =>
          log.info(s"Hasn't generated tx")
      }

    case StateModified => viewHolderRef ! getTransaction
  }

  def roundDataIsPosted(roundNum: Int, view: NodeView): Boolean = {

    ownSigningKeyPairOpt match {
      case Some(ownSigningKeyPair) =>

        val signingPubKey = ownSigningKeyPair.publicImage

        val pending = roundNum match {
          case 1 =>
            view.pool.unconfirmed.values.exists {
              case txPool: DKGr1Transaction => txPool.pubKey == signingPubKey
              case _ => false
            }
          case 2 =>
            view.pool.unconfirmed.values.exists {
              case txPool: DKGr2Transaction => txPool.pubKey == signingPubKey
              case _ => false
            }
          case 3 =>
            view.pool.unconfirmed.values.exists {
              case txPool: DKGr3Transaction => txPool.pubKey == signingPubKey
              case _ => false
            }
          case 4 =>
            view.pool.unconfirmed.values.exists {
              case txPool: DKGr4Transaction => txPool.pubKey == signingPubKey
              case _ => false
            }
          case 5 =>
            view.pool.unconfirmed.values.exists {
              case txPool: DKGr5Transaction => txPool.pubKey == signingPubKey
              case _ => false
            }
          case _ => false
        }

        val ownId = view.trState.getApprovedCommitteeInfo.indexWhere(_.signingKey == signingPubKey)

        val accepted = roundNum match {
          case 1 => view.trState.getDKGr1Data.contains(ownId)
          case 2 => view.trState.getDKGr2Data.contains(ownId)
          case 3 => view.trState.getDKGr3Data.contains(ownId)
          case 4 => view.trState.getDKGr4Data.contains(ownId)
          case 5 => view.trState.getDKGr5Data.contains(ownId)
          case _ => false
        }
        pending || accepted

      case _ => false // no any data could have been posted without own signing key pair
    }
  }

  private def getTx(view: NodeView): Option[TreasuryTransaction] = {

    def logResult(tx: Option[TreasuryTransaction], dataName: String): Option[TreasuryTransaction] = {
      tx match {
        case Some(_) =>
          log.info(s"$dataName transaction is generated successfully")

        case _ =>
          log.info(s"[ERROR] $dataName transaction wasn't generated!")
      }
      tx
    }

    import examples.hybrid.TreasuryManager._
    val epochHeight = view.history.height % TreasuryManager.EPOCH_LEN

    if (dkgOpt.isEmpty)
      dkgOpt = initDKG(view)

    if (dkgOpt.isDefined) {

      log.info(s"DKG state is: ${dkgOpt.get.getRoundsPassed}")

      epochHeight match {

        case h if DISTR_KEY_GEN_R1_RANGE.contains(h) && !roundDataIsPosted(1, view) && dkgOpt.get.getRoundsPassed == 0 =>

          logResult(round1DkgTx(dkgOpt, view), "R1Data")

        case h if DISTR_KEY_GEN_R2_RANGE.contains(h) && !roundDataIsPosted(2, view) && dkgOpt.get.getRoundsPassed == 1 =>

          val r1DataSeq = view.trState.getDKGr1Data.values.toSeq
          logResult(round2DkgTx(r1DataSeq, dkgOpt, view), "R2Data")

        case h if DISTR_KEY_GEN_R3_RANGE.contains(h) && !roundDataIsPosted(3, view) && dkgOpt.get.getRoundsPassed == 2 =>

          val r2DataSeq = view.trState.getDKGr2Data.values.toSeq
          logResult(round3DkgTx(r2DataSeq, dkgOpt, view), "R3Data")

        case h if DISTR_KEY_GEN_R4_RANGE.contains(h) && !roundDataIsPosted(4, view) && dkgOpt.get.getRoundsPassed == 3 =>

          val r3DataSeq = view.trState.getDKGr3Data.values.toSeq
          logResult(round4DkgTx(r3DataSeq, dkgOpt, view), "R4Data")

        case h if DISTR_KEY_GEN_R5_RANGE.contains(h) && !roundDataIsPosted(5, view) && dkgOpt.get.getRoundsPassed == 4 =>

          val r4DataSeq = view.trState.getDKGr4Data.values.toSeq
          logResult(round5DkgTx(r4DataSeq, dkgOpt, view), "R5Data")

        case h if h >= DISTR_KEY_GEN_R5_RANGE.end && sharedPublicKeyOpt.isEmpty && dkgOpt.get.getRoundsPassed == 5 =>

          // Just get shared public key and restored secret keys for internal state, without posting a transaction

          val r5DataSeq = view.trState.getDKGr5Data.values.toSeq

          dkgOpt match {
            case Some(dkg) =>

              dkg.doRound5_2(r5DataSeq) match {
                case Some(r6Data) =>
                  sharedPublicKeyOpt = Some(cs.decodePoint(r6Data.sharedPublicKey))
                  log.info(s"Shared public key: ${Base58.encode(sharedPublicKeyOpt.get.getEncoded(true))}")
                  dkgViolatorsSecretKeys = Some(r6Data.violatorsSecretKeys)
                case _ =>
              }
            case _ =>
          }
          None

        case _ =>
          log.info(s"Current height: ${epochHeight}")
          None
      }
    } else { // !dkgOpt.isDefined
      log.info(s"ERROR: State wasn't restored!")
      None
    }
  }

  def getRoundsData(view: NodeView): Option[RoundsData] = {

    ownSigningKeyPairOpt match {
      case Some(ownSigningKeyPair) =>

        val signingPubKey = ownSigningKeyPair.publicImage
        val ownId = view.trState.getApprovedCommitteeInfo.indexWhere(_.signingKey == signingPubKey)
        val epochCurrentHeight = view.history.height % TreasuryManager.EPOCH_LEN

        // Data from mempool for the current round
        val ownDataInMempoolOpt = epochCurrentHeight match {

          case h if DISTR_KEY_GEN_R1_RANGE.contains(h) =>
            // Seraching data for the current round in the mempool
            val r1TxMempoolOpt = view.pool.unconfirmed.values.find {
              case txPool: DKGr1Transaction => txPool.pubKey == signingPubKey
              case _ => false
            }
            r1TxMempoolOpt match {
              case Some(r1TxMempool: DKGr1Transaction) =>
                // If data is existing in the mempool, verify if it is also existing in the history
                // (the same data both in the mempool and in the history is an abnormal case, but it should be handled)
                view.trState.getDKGr1Data.find(_._1 == ownId) match {
                  case None => Some(RoundsData(r1Data = Seq(r1TxMempool.r1Data))) // use data from mempool only if it is not already in the history
                  case _ => Some(RoundsData()) // data is already in the history, so won't take it from the mempool
                }
              case _ => Some(RoundsData()) // data for a current round is absent in the mempool
            }

          case h if DISTR_KEY_GEN_R2_RANGE.contains(h) =>
            val r2TxMempoolOpt = view.pool.unconfirmed.values.find {
              case txPool: DKGr2Transaction => txPool.pubKey == signingPubKey
              case _ => false
            }
            r2TxMempoolOpt match {
              case Some(r2TxMempool: DKGr2Transaction) =>
                view.trState.getDKGr2Data.find(_._1 == ownId) match {
                  case None => Some(RoundsData(r2Data = Seq(r2TxMempool.r2Data)))
                  case _ => Some(RoundsData())
                }
              case _ => Some(RoundsData())
            }

          case h if DISTR_KEY_GEN_R3_RANGE.contains(h) =>
            val r3TxMempoolOpt = view.pool.unconfirmed.values.find {
              case txPool: DKGr3Transaction => txPool.pubKey == signingPubKey
              case _ => false
            }
            r3TxMempoolOpt match {
              case Some(r3TxMempool: DKGr3Transaction) =>
                view.trState.getDKGr3Data.find(_._1 == ownId) match {
                  case None => Some(RoundsData(r3Data = Seq(r3TxMempool.r3Data)))
                  case _ => Some(RoundsData())
                }
              case _ => Some(RoundsData())
            }

          case h if DISTR_KEY_GEN_R4_RANGE.contains(h) =>
            val r4TxMempoolOpt = view.pool.unconfirmed.values.find {
              case txPool: DKGr4Transaction => txPool.pubKey == signingPubKey
              case _ => false
            }
            r4TxMempoolOpt match {
              case Some(r4TxMempool: DKGr4Transaction) =>
                view.trState.getDKGr4Data.find(_._1 == ownId) match {
                  case None => Some(RoundsData(r4Data = Seq(r4TxMempool.r4Data)))
                  case _ => Some(RoundsData())
                }
              case _ => Some(RoundsData())
            }

          case h if DISTR_KEY_GEN_R5_RANGE.contains(h) =>
            val r5TxMempoolOpt = view.pool.unconfirmed.values.find {
              case txPool: DKGr5Transaction => txPool.pubKey == signingPubKey
              case _ => false
            }
            r5TxMempoolOpt match {
              case Some(r5TxMempool: DKGr5Transaction) =>
                view.trState.getDKGr5Data.find(_._1 == ownId) match {
                  case None => Some(RoundsData(r5_1Data = Seq(r5TxMempool.r5_1Data)))
                  case _ => Some(RoundsData())
                }
              case _ => Some(RoundsData())
            }

          case h if h >= DISTR_KEY_GEN_R5_RANGE.end =>
            Some(RoundsData()) // all own rounds data should already be in the history after the 5-th round

          case _ => None
        }

        ownDataInMempoolOpt match {
          case Some(ownDataInMempool) =>
            Some(
              RoundsData(
                view.trState.getDKGr1Data.values.toSeq ++ ownDataInMempool.r1Data,
                view.trState.getDKGr2Data.values.toSeq ++ ownDataInMempool.r2Data,
                view.trState.getDKGr3Data.values.toSeq ++ ownDataInMempool.r3Data,
                view.trState.getDKGr4Data.values.toSeq ++ ownDataInMempool.r4Data,
                view.trState.getDKGr5Data.values.toSeq ++ ownDataInMempool.r5_1Data
              )
            )
          case _ => None
        }
      case _ => None
    }
  }

  private var dkgOpt: Option[DistrKeyGen] = None
  private var ownSecretKeyOpt: Option[PrivKey] = None   // secret key
  private var ownKeyPairOpt: Option[KeyPair] = None     // transport key-pair
  private var ownSigningKeyPairOpt: Option[PrivateKey25519] = None
  private var sharedPublicKeyOpt: Option[PubKey] = None
  private var dkgViolatorsSecretKeys: Option[Array[SecretKey]] = None

  private def initDKG(view: NodeView): Option[DistrKeyGen] = {

    val cs = view.trState.cs
    val crs_h = view.trState.crs_h

    dkgViolatorsSecretKeys = None
    sharedPublicKeyOpt = None

    val committeeMembersPubKeys = view.trState.getApprovedCommitteeInfo.map(_.proxyKey)
    val memberIdentifier = new SimpleIdentifier(committeeMembersPubKeys)

    ownSigningKeyPairOpt = view.vault.treasurySigningSecrets(view.trState.epochNum).headOption match {
      case Some(treasurySecret) => Some(treasurySecret.privKey)
      case _ => None
    }

    ownKeyPairOpt = view.vault.treasuryCommitteeSecrets(view.trState.epochNum).headOption match {
      case Some(secret) =>
        ownSecretKeyOpt = Some(secret.secretKey)
        Some(secret.privKey, secret.pubKey)
      case _ => None
    }

    ownKeyPairOpt match {
      case Some(ownKeyPair) =>

        getRoundsData(view) match {
        case Some(roundsData) =>

            Try{
              new DistrKeyGen(cs,
                crs_h,
                (ownKeyPair._1, ownKeyPair._2),
                ownSecretKeyOpt.get, // ownSecretKeyOpt always initializes together with ownKeyPairOpt
                committeeMembersPubKeys,
                memberIdentifier,
                roundsData)
            }.toOption

          case _ => None
        }
      case _ => None
    }
  }

  private def validateTx(view: NodeView, trsryTx: TreasuryTransaction): Boolean = {

    val pending = trsryTx match {

      case txToValidate: DKGr1Transaction =>
        view.pool.unconfirmed.values.exists {
          case txPool: DKGr1Transaction => txPool.pubKey == txToValidate.pubKey
          case _ => false
      }
      case txToValidate: DKGr2Transaction =>
        view.pool.unconfirmed.values.exists {
          case txPool: DKGr2Transaction => txPool.pubKey == txToValidate.pubKey
          case _ => false
        }
      case txToValidate: DKGr3Transaction =>
        view.pool.unconfirmed.values.exists {
          case txPool: DKGr3Transaction => txPool.pubKey == txToValidate.pubKey
          case _ => false
        }
      case txToValidate: DKGr4Transaction =>
        view.pool.unconfirmed.values.exists {
          case txPool: DKGr4Transaction => txPool.pubKey == txToValidate.pubKey
          case _ => false
        }
      case txToValidate: DKGr5Transaction =>
        view.pool.unconfirmed.values.exists {
          case txPool: DKGr5Transaction => txPool.pubKey == txToValidate.pubKey
          case _ => false
        }

      case _ => false
    }

    val isValid = Try(new TreasuryTxValidator(view.trState, view.history.height, Some(view.history))).flatMap(_.validate(trsryTx)).isSuccess
    !pending && isValid
  }

  private def round1DkgTx(dkgOpt: Option[DistrKeyGen], view: NodeView): Option[TreasuryTransaction] = {

    println("DKG Round1 started")

    dkgOpt match {
      case Some(dkg) =>

        dkg.doRound1() match {
          case Some(r1Data) =>

            ownSigningKeyPairOpt match {
              case Some(ownSigningKeyPair) =>

                DKGr1Transaction.create(ownSigningKeyPair, r1Data, view.trState.epochNum) match {
                  case Success(tx) if validateTx(view, tx) => Some(tx)
                  case _ => None
                }
              case _ => None
            }
          case _ => None
        }
      case _ => None
    }
  }

  private def round2DkgTx(r1Data: Seq[R1Data], dkgOpt: Option[DistrKeyGen], view: NodeView): Option[TreasuryTransaction] = {

    println("DKG Round2 started")

    dkgOpt match {
      case Some(dkg) =>

        dkg.doRound2(r1Data) match {
          case Some(r2Data) =>

            ownSigningKeyPairOpt match {
              case Some(ownSigningKeyPair) =>

                DKGr2Transaction.create(ownSigningKeyPair, r2Data, view.trState.epochNum) match {
                  case Success(tx) if validateTx(view, tx) => Some(tx)
                  case _ => None
                }
              case _ => None
            }
          case _ => None
        }
      case _ => None
    }
  }

  private def round3DkgTx(r2Data: Seq[R2Data], dkgOpt: Option[DistrKeyGen], view: NodeView): Option[TreasuryTransaction] = {

    println("DKG Round3 started")

    dkgOpt match {
      case Some(dkg) =>

        dkg.doRound3(r2Data) match {
          case Some(r3Data) =>

            ownSigningKeyPairOpt match {
              case Some(ownSigningKeyPair) =>

                DKGr3Transaction.create(ownSigningKeyPair, r3Data, view.trState.epochNum) match {
                  case Success(tx) if validateTx(view, tx) => Some(tx)
                  case _ => None
                }
              case _ => None
            }
          case _ => None
        }
      case _ => None
    }
  }

  private def round4DkgTx(r3Data: Seq[R3Data], dkgOpt: Option[DistrKeyGen], view: NodeView): Option[TreasuryTransaction] = {

    println("DKG Round4 started")

    dkgOpt match {
      case Some(dkg) =>

        dkg.doRound4(r3Data) match {
          case Some(r4Data) =>

            ownSigningKeyPairOpt match {
              case Some(ownSigningKeyPair) =>

                DKGr4Transaction.create(ownSigningKeyPair, r4Data, view.trState.epochNum) match {
                  case Success(tx) if validateTx(view, tx) => Some(tx)
                  case _ => None
                }
              case _ => None
            }
          case _ => None
        }
      case _ => None
    }
  }

  private def round5DkgTx(r4Data: Seq[R4Data], dkgOpt: Option[DistrKeyGen], view: NodeView): Option[TreasuryTransaction] = {

    println("DKG Round5 started")

    dkgOpt match {
      case Some(dkg) =>

        dkg.doRound5_1(r4Data) match {
          case Some(r5Data) =>

            ownSigningKeyPairOpt match {
              case Some(ownSigningKeyPair) =>

                DKGr5Transaction.create(ownSigningKeyPair, r5Data, view.trState.epochNum) match {
                  case Success(tx) if validateTx(view, tx) => Some(tx)
                  case _ => None
                }
              case _ => None
            }
          case _ => None
        }
      case _ => None
    }
  }
}

object CommitteeMember {

  type NodeView = CurrentViewWithTreasuryState[HybridHistory, HBoxStoredState, HWallet, SimpleBoxTransactionMemPool]

  case class TxInfo(tx: Option[TreasuryTransaction])

  case object StateModified

  private var committeeMember: Option[ActorRef] = None
  implicit val system: ActorSystem = ActorSystem()

  def getMember(viewHolderRef: ActorRef): Option[ActorRef] = {
    dispatchMember(viewHolderRef)
    committeeMember.synchronized(committeeMember)
  }

  private def dispatchMember(viewHolderRef: ActorRef): Unit = {

    def isRegisteredAsCommitteeMember(view: NodeView): Boolean = {

      val localSigningPubKeyOpt = view.vault.treasurySigningSecrets(view.trState.epochNum).headOption.map(_.privKey.publicImage)

      // Check if current epoch treasury state contains given signing public key (this means the key is registered)
      localSigningPubKeyOpt match {
        case Some(localSigningPubKey) =>
          view.trState.getApprovedCommitteeInfo.exists(_.signingKey == localSigningPubKey)
        case None => false
      }
    }

    def f(view: NodeView): NodeView = view

    import akka.pattern.ask
    import scala.concurrent.duration._

    implicit val duration: Timeout = 20.seconds

    import scala.concurrent._
    import ExecutionContext.Implicits.global

    (viewHolderRef ? GetDataFromCurrentViewWithTreasuryState[HybridHistory,
      HBoxStoredState,
      HWallet,
      SimpleBoxTransactionMemPool,
      NodeView](f)).onComplete {
      case Success(v) =>
        val view = v.asInstanceOf[NodeView]
        println(s"epochHeight: ${view.history.height % TreasuryManager.EPOCH_LEN}")
        if (isRegisteredAsCommitteeMember(view)){
          startOrStopMember(view, viewHolderRef)
        } else {
          println("CommitteeMember: dispatchMember: Isn't registered as a Committee Member")
        }
      case Failure(e) =>
        println("CommitteeMember: dispatchMember: Node View wasn't obtained")
        e.printStackTrace()
    }
  }

  private def startOrStopMember(view: NodeView, viewHolderRef: ActorRef): Unit = {

    // This code can be executed asynchronously, so synchronization for the shared object committeeMember is needed
    committeeMember.synchronized {
      val history = view.history
      val epochHeight = history.height % TreasuryManager.EPOCH_LEN

      if (epochHeight >= DISTR_KEY_GEN_R1_RANGE.start && // epochHeight <  PAYMENT_BLOCK_HEIGHT
          epochHeight <  DISTR_KEY_GEN_R5_RANGE.end) {
        committeeMember match {
          case None => committeeMember = Some(CommitteeMemberRef(viewHolderRef))
          case Some(_) =>
        }
      } else {
        committeeMember match {
          case Some(cm) =>
            cm ! PoisonPill
            committeeMember = None
          case None =>
        }
      }
    }
  }

  def stopMember(): Unit = {
    committeeMember.synchronized {
      committeeMember match {
        case Some(cm) =>
          system.stop(cm)
          committeeMember = None
        case None =>
      }
    }
  }
}

object CommitteeMemberRef {

  def props(viewHolderRef: ActorRef): Props = Props(new CommitteeMember(viewHolderRef))

  def apply(viewHolderRef: ActorRef)
           (implicit system: ActorSystem): ActorRef = system.actorOf(props(viewHolderRef))

  def apply(name: String, viewHolderRef: ActorRef)
           (implicit system: ActorSystem): ActorRef = system.actorOf(props(viewHolderRef), name)
}
