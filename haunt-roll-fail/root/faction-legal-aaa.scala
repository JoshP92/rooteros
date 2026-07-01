package root

import hrf.colmat._
import hrf.logger._
import hrf.elem._
import root.elem._
import hrf.options._
import hrf.meta._

/*
Twilight Council  (Root fan faction — Homeland Expansion; codename LegalAAA / TCvA)

The Council hosts assemblies to end the war, pushing factions away from bloody battle and
toward heated debate. Rules reference: https://root.fandom.com/wiki/Twilight_Council and
Law of Root: Homeland Expansion.

Birdsong — reveal cards one-by-one to Act (move / recruit / battle / Assemble) in a matching
  clearing. Assembling in a clearing with no assembly places a Closed assembly (+ Loyalists),
  then discard the revealed card if you don't rule.
Daylight — flip every assembly ruled by an enemy to its Closed side.
Evening — Convene Woodfolk: return revealed cards to hand; per returned card act at a matching
  assembly (Banish / Agitate / Empower / Rejoice). Then craft with assemblies (or draw 1 per 2
  assemblies), Adjourn (flip your ruled assemblies to Empowered), Oversee (score for Empowered
  assemblies at enemy pieces), then draw.
Abilities — Governors (Empowered assemblies govern their clearing; enemies can't craft/flip/
  place/remove there except in battle), Entreating, Peacekeepers (join the defender in battles
  between enemies at an assembly; take hits after them; don't defend the Vagabond).

NOTE (rewrite status): this file was previously non-compiling AI-generated pseudo-code. It has
been rebuilt against the real engine API. Faction definition, pieces, state, setup, and the
cross-faction Convene / Outrage / Peacekeepers battle hooks are wired; the full Birdsong /
Daylight / Evening action set is being implemented incrementally (marked TODO below). Until
those are filled in, the Council passes its turn phases, so it is selectable and does not stall
the game, but is not yet fully playable.
*/

// ---------------- Pieces ----------------

trait CommonBatWarrior extends Warrior

case object BatAAA extends CommonBatWarrior {
    override def id = "bat"
    override def name = "Bat"
}

case object Loyalist extends Warrior {
    override def id = "loyalist"
    override def name = "Loyalist"
}

// A "commune" board token (drawn by ui.scala; asset tc-commune).
case object CommuneAAA extends Token {
    override def id = "commune"
    override def name = "Commune"
    override def imgid(f : Faction) = "tc-commune"
}

// Assembly token (referenced as a value by game.scala/ui.scala).
case object AssemblyAAA extends Token {
    override def id = "assembly"
    override def name = "Assembly"
    override def imgid(f : Faction) = "tc-assembly"
}

// The "convened" / empowered state of an assembly; governs its clearing.
case object ConvenedAAA extends Token {
    override def id = "convened"
    override def name = "Convened"
    override def imgid(f : Faction) = "tc-convened"
}

// A board token used by the cross-faction Outrage check in battle.scala. No bat tokens are
// placed by the current logic, so that Outrage trigger is dormant until Birdsong is implemented.
case class BatTokenAAA() extends Token {
    override def id = "bat-token"
    override def name = "Bat"
    override def imgid(f : Faction) = "tc-bat"
}

// ---------------- Effects ----------------

case object Governors extends Effect
case object Entreating extends Effect
case object PeacekeepersAAA extends Effect

// Referenced across battle/fanatic/feline/game/hirelings.
case object ConveneInstead extends Effect
case object IgnoreWarriorRemovalEffects extends Effect

// ---------------- Faction ----------------

case object TwilightCouncil extends WarriorFaction {
    val name = "Twilight Council"
    val short = "TCvA"
    val style = "tc"
    val priority = "S"

    val warrior = BatAAA

    def abilities(options : $[Meta.O]) = $(Governors, Entreating, PeacekeepersAAA)

    def pieces(options : $[Meta.O]) = BatAAA *** 20 ++ AssemblyAAA *** 6 ++ ConvenedAAA *** 6

    def advertising = BatAAA.img(this)
    def motto = "Debate".styled(this)

    override def note : Elem = HorizontalBreak ~ "Fan Faction"

    // A clearing is governed by the Council when it holds a Convened (empowered) assembly.
    def governs(c : Region)(implicit game : Game) : Boolean =
        this.at(c).has(ConvenedAAA)

    def clashKey = this
}

// ---------------- State ----------------

class PlayerState(val faction : TwilightCouncil.type)(implicit val game : Game) extends FactionState {
    // Crafting is unimplemented for now (the Council would craft with its assemblies).
    def craft = $[SuitAsset]()
}

// ---------------- Cross-faction / battle-hook actions ----------------

// The attacker may Debate (Convene) at a Council assembly instead of battling.
case class LegalAAAConveneAction(f : Faction, l : LegalAAA, c : Clearing, o : Faction, then : ForcedAction) extends ForcedAction

// Reaction analogous to the Woodland Alliance's OutrageAction.
case class LegalAAAOutrageAction(f : LegalAAA, o : Faction, c : Clearing, then : ForcedAction) extends ForcedAction

// ---------------- Expansion / perform ----------------

object LegalAAAExpansion extends FactionExpansion[TwilightCouncil.type] {
    def perform(action : Action, soft : Void)(implicit game : Game) = action @@ {
        // Convene: the attacker debates with the Council instead of the battle proceeding.
        case LegalAAAConveneAction(f, l, c, o, then) =>
            f.log("debates with", l, "at", c, "instead of battling", o)
            then

        // Outrage reaction placeholder, then continue the battle flow.
        case LegalAAAOutrageAction(f, o, c, then) =>
            then

        // SETUP
        case CreatePlayerAction(f : TwilightCouncil.type) =>
            game.states += f -> new PlayerState(f)
            FactionInitAction(f)

        case FactionSetupAction(f : TwilightCouncil.type) =>
            // TODO: real advanced setup (homeland clearing: 4 warriors + 1 Empowered assembly;
            // 2 warriors elsewhere; remaining assemblies Closed in reserve).
            SetupFactionsAction

        // TURN — pass each phase for now (TODO: implement Birdsong/Daylight/Evening actions).
        case BirdsongNAction(_, f : TwilightCouncil.type) =>
            Ask(f)(Next.as("Birdsong")).birdsong(f)

        case DaylightNAction(_, f : TwilightCouncil.type) =>
            Ask(f)(Next.as("Daylight")).daylight(f)

        case EveningNAction(_, f : TwilightCouncil.type) =>
            Ask(f)(Next.as("Evening")).evening(f)

        case _ => UnknownContinue
    }
}
