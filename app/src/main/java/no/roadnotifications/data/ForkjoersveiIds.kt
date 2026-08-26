package no.roadnotifications.data

/**
 * NVDB type 596 reuses one vegobjekt-id across many linestrings.
 * Extra imported rows keep a unique SQLite primary key while
 * [nvdbId] recovers the original id for enter-once tracking.
 */
object ForkjoersveiIds {
    private const val EXTRA_ROW_FLAG = 1L shl 62
    private const val NVDB_ID_BITS = 46
    private const val OCCURRENCE_BITS = 16
    private const val NVDB_ID_MASK = (1L shl NVDB_ID_BITS) - 1L

    fun nvdbId(rowId: Long): Long {
        if (rowId and EXTRA_ROW_FLAG == 0L) {
            return rowId
        }
        return (rowId ushr OCCURRENCE_BITS) and NVDB_ID_MASK
    }

    fun stretchGroupId(vegObjekt: VegObjektEntity): Long {
        return if (vegObjekt.type == VegObjektType.FORKJOERSVEI.name) {
            nvdbId(vegObjekt.id)
        } else {
            vegObjekt.id
        }
    }
}
