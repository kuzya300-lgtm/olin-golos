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
        embedding = OfflineSpeakerEmbeddingExtractorConfig(
            model = embeddingModelPath
        ),
        clustering = OfflineSpeakerClusteringConfig(
            numClusters = numSpeakers,
            threshold = 0.5f
        ),
        minDurationOn = 0.3f,
        minDurationOff = 0.5f
    )

    val diarization = OfflineSpeakerDiarization(config)
    val waveData = WaveReader.readWaveFromFile(audioFilePath)
    val result = diarization.process(waveData)
    diarization.release()

    return result.segments.map {
        SpeakerSegment(start = it.start, end = it.end, speaker = it.speaker)
    }
}
