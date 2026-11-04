package it.ponte

/** Copia veloce della memoria del motore delle telecamere. */
object CamMem {
    val ok: Boolean = try { System.loadLibrary("relaycam"); true } catch (_: Throwable) { false }
    @JvmStatic external fun copy(addr: Long, dst: java.nio.ByteBuffer, len: Int)
}
