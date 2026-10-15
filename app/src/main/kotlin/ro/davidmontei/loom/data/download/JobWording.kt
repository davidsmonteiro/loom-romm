package ro.davidmontei.loom.data.download

import ro.davidmontei.loom.storage.engine.WaitReason

/**
 * Why a waiting job isn't moving, in the queue's words. The library also says where a game waiting
 * for its folder is asked about ([inLibrary]): the queue.
 */
fun waitWording(reason: WaitReason?, inLibrary: Boolean = false): String = when (reason) {
    WaitReason.STORAGE -> "Waiting for the SD card"
    WaitReason.FOLDER -> if (inLibrary) "Choose its folder in the queue" else "Choose its folder"
    WaitReason.APP_OPEN, null -> "Waiting for Loom to open"
}
