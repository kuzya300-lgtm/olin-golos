package com.govorun.lite

import com.k2fsa.sherpa.onnx.*

data class SpeakerSegment(
    val start: Float,
    val end: Float,
    val speaker: Int
)

fun runSpeakerDiarization(
    audioFilePath: String,
    segmentationModelPath: String,
    embeddingModelPath: String,
    numSpeakers: Int = -1
): List<SpeakerSegment> {
    val config = OfflineSpeakerDiarizationConfig(
        segmentation = OfflineSpeakerSegmentationModelConfig(
            pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                model = segmentationModelPath
            )
        ),
        // ИСПОЛЬЗУЕМ ПРАВИЛЬНЫЙ КЛАСС
        embedding = SpeakerEmbeddingExtractorConfig(
            model = embeddingModelPath
        ),
        // ИСПОЛЬЗУЕМ ПРАВИЛЬНЫЙ КЛАСС
        clustering = FastClusteringConfig(
            numClusters = numSpeakers,
            threshold = 0.5f
        )
    )

    val sd = OfflineSpeakerDiarization(config = config)
    val waveData = WaveReader.readWave(filename = audioFilePath)

    // process принимает FloatArray (waveData.samples), а не весь WaveData
    val result = sd.process(waveData.samples)

    return result.map {
        SpeakerSegment(
            start = it.start,
            end = it.end,
            speaker = it.speaker
        )
    }
}
