package com.rm.interchange.sim

/**
 * Commands as bytes, for saves, the network and replays. Each starts with its
 * kind; new kinds go on the end and old ones never change, so old recordings
 * keep reading.
 */
object Codec {
    private const val TERRAFORM = 1
    private const val RAIL = 2
    private const val STATION = 3
    private const val DEPOT = 4
    private const val SIGNAL = 5
    private const val CLEAR = 6
    private const val BUY_TRAIN = 7
    private const val TRAIN_ORDER = 8
    /** A train bought with what its wagons carry. */
    private const val BUY_TRAIN_CARGO = 9
    private const val LOAN = 10

    fun write(w: ByteWriter, c: Command) {
        when (c) {
            is Command.Terraform -> { w.byte(TERRAFORM); w.int(c.company); w.int(c.x); w.int(c.y); w.bool(c.up) }
            is Command.Rail -> { w.byte(RAIL); w.int(c.company); w.int(c.sx); w.int(c.sy); w.int(c.ex); w.int(c.ey); w.int(c.first); w.bool(c.remove) }
            is Command.Station -> { w.byte(STATION); w.int(c.company); w.int(c.x0); w.int(c.y0); w.int(c.x1); w.int(c.y1); w.int(c.axis) }
            is Command.Depot -> { w.byte(DEPOT); w.int(c.company); w.int(c.x); w.int(c.y); w.int(c.edge) }
            is Command.Signal -> { w.byte(SIGNAL); w.int(c.company); w.int(c.x); w.int(c.y); w.int(c.piece); w.int(c.facing) }
            is Command.Clear -> { w.byte(CLEAR); w.int(c.company); w.int(c.x); w.int(c.y) }
            is Command.BuyTrain -> { w.byte(BUY_TRAIN_CARGO); w.int(c.company); w.int(c.depot); w.int(c.engine); w.int(c.wagon); w.int(c.wagons); w.int(c.cargo) }
            is Command.Loan -> { w.byte(LOAN); w.int(c.company); w.bool(c.borrow) }
            is Command.TrainOrder -> { w.byte(TRAIN_ORDER); w.int(c.company); w.int(c.train); w.int(c.what); w.ints(c.stations) }
        }
    }

    fun read(r: ByteReader): Command = when (val kind = r.byte()) {
        TERRAFORM -> Command.Terraform(r.int(), r.int(), r.int(), r.bool())
        RAIL -> Command.Rail(r.int(), r.int(), r.int(), r.int(), r.int(), r.int(), r.bool())
        STATION -> Command.Station(r.int(), r.int(), r.int(), r.int(), r.int(), r.int())
        DEPOT -> Command.Depot(r.int(), r.int(), r.int(), r.int())
        SIGNAL -> Command.Signal(r.int(), r.int(), r.int(), r.int(), r.int())
        CLEAR -> Command.Clear(r.int(), r.int(), r.int())
        BUY_TRAIN -> Command.BuyTrain(r.int(), r.int(), r.int(), r.int(), r.int())
        TRAIN_ORDER -> Command.TrainOrder(r.int(), r.int(), r.int(), r.ints())
        BUY_TRAIN_CARGO -> Command.BuyTrain(r.int(), r.int(), r.int(), r.int(), r.int(), r.int())
        LOAN -> Command.Loan(r.int(), r.bool())
        else -> throw BadData("no such command: $kind")
    }

    fun encode(c: Command): ByteArray = ByteWriter(32).also { write(it, c) }.toByteArray()
    fun decode(b: ByteArray): Command = read(ByteReader(b))
}
