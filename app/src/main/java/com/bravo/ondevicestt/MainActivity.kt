package com.bravo.ondevicestt

import android.app.Activity
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.*
import androidx.documentfile.provider.DocumentFile
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var treeUri: Uri? = null
    private var selectedAudio: DocumentFile? = null
    private lateinit var status: TextView
    private lateinit var fileText: TextView
    private lateinit var transcript: TextView
    private lateinit var fileList: LinearLayout
    private var recognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        treeUri = getSharedPreferences("p",0).getString("tree",null)?.let(Uri::parse)
        buildUi()
    }

    override fun onDestroy() {
        scope.cancel()
        try { recognizer?.close() } catch (_:Exception) {}
        super.onDestroy()
    }

    private fun buildUi() {
        val scroll=ScrollView(this)
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setPadding(28,32,28,50)
        }
        scroll.addView(root); setContentView(scroll)
        root.addView(tv("BRAVO On-Device STT",28,true))
        root.addView(tv("v0.7 · API Key 없음 · 한국어 기기내 전사",15,false))
        root.addView(btn("1. Recordings/Call 폴더 선택"){ chooseFolder() })
        fileText=tv(treeUri?.toString() ?: "폴더 미선택",12,false); root.addView(fileText)
        root.addView(btn("2. 최근 녹음 불러오기"){ loadFiles() })
        fileList=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}; root.addView(fileList)
        root.addView(btn("3. 기기내 한국어 모델 진단"){ checkModel() })
        status=tv("대기 중",15,true); root.addView(status)
        root.addView(btn("4. 선택 녹음 → 온디바이스 전사"){ transcribeSelected() })
        transcript=tv("전사 결과가 여기에 표시됩니다.",15,false); root.addView(transcript)
    }

    private fun chooseFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        },200)
    }

    @Deprecated("legacy")
    override fun onActivityResult(req:Int,res:Int,data:Intent?) {
        super.onActivityResult(req,res,data)
        if(req==200 && res==RESULT_OK && data?.data!=null) {
            treeUri=data.data
            try { contentResolver.takePersistableUriPermission(treeUri!!,Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch(_:Exception){}
            getSharedPreferences("p",0).edit().putString("tree",treeUri.toString()).apply()
            fileText.text=treeUri.toString()
            loadFiles()
        }
    }

    private fun loadFiles() {
        val uri=treeUri ?: return toast("Call 폴더를 먼저 선택하세요.")
        fileList.removeAllViews()
        var dir=DocumentFile.fromTreeUri(this,uri) ?: return
        if(!dir.name.equals("Call",true)) dir.listFiles().firstOrNull{it.isDirectory && it.name.equals("Call",true)}?.let{dir=it}
        val audios=dir.listFiles().filter{it.isFile && (it.name?.lowercase()?.endsWith(".m4a")==true)}
            .sortedByDescending{it.lastModified()}.take(15)
        audios.forEach { f ->
            val label = (if (f == selectedAudio) "✓ " else "") + (f.name ?: "녹음")
            fileList.addView(btn(label) {
                selectedAudio = f
                fileText.text = "선택: ${f.name}"
                loadFiles()
            })
        }
        status.text="최근 m4a ${audios.size}개 표시"
    }

    private fun makeRecognizer(): SpeechRecognizer {
        recognizer?.close()
        val options=speechRecognizerOptions {
            locale=Locale.KOREA
            preferredMode=SpeechRecognizerOptions.Mode.MODE_BASIC
        }
        return SpeechRecognition.getClient(options).also{recognizer=it}
    }

    private fun checkModel() {
        status.text="한국어 온디바이스 모델 확인 중…"
        scope.launch {
            try {
                val r=makeRecognizer()
                when(val s=r.checkStatus()) {
                    FeatureStatus.AVAILABLE -> status.text="✅ 한국어 기기내 모델 사용 가능"
                    FeatureStatus.DOWNLOADABLE -> {
                        status.text="모델 다운로드 필요 · 다운로드 시작"
                        r.download.collect { d -> status.text="모델 다운로드: $d" }
                    }
                    else -> status.text="⚠️ 현재 기기 상태: $s"
                }
            } catch(e:Exception) { status.text="진단 오류: ${e.message}" }
        }
    }

    private fun transcribeSelected() {
        val f=selectedAudio ?: return toast("먼저 녹음 하나를 선택하세요.")
        status.text="1/3 m4a 디코딩 및 16kHz mono PCM 변환 중…"
        transcript.text=""
        scope.launch {
            try {
                val pcm=withContext(Dispatchers.IO){ decodeTo16kMonoPcm(f.uri) }
                status.text="2/3 PCM 준비 완료 (${pcm.length()/32000}초) · 모델 확인 중…"
                val r=makeRecognizer()
                val fs=r.checkStatus()
                if(fs==FeatureStatus.DOWNLOADABLE) {
                    status.text="기기내 한국어 모델 다운로드 중…"
                    r.download.collect{}
                } else if(fs!=FeatureStatus.AVAILABLE) {
                    throw IllegalStateException("기기내 모델 사용 불가: $fs")
                }
                status.text="3/3 실시간 속도로 기기내 전사 중…"
                runRecognition(r,pcm)
            } catch(e:Exception) {
                status.text="❌ 전사 실패: ${e.javaClass.simpleName}"
                transcript.text=e.message ?: e.toString()
            }
        }
    }

    private suspend fun runRecognition(r:SpeechRecognizer, pcm:File) = coroutineScope {
        val pipe=ParcelFileDescriptor.createPipe()
        val reader=pipe[0]; val writer=pipe[1]

        val feeder=launch(Dispatchers.IO) {
            FileOutputStream(writer.fileDescriptor).use { out ->
                pcm.inputStream().use { input ->
                    val chunk=ByteArray(3200) // 100ms @ 16k mono 16-bit
                    while(isActive) {
                        val n=input.read(chunk); if(n<=0) break
                        out.write(chunk,0,n); out.flush()
                        delay((n/32.0).roundToInt().toLong()) // 32 bytes/ms
                    }
                }
            }
            try { writer.close() } catch(_:Exception){}
        }

        val request=speechRecognizerRequest { audioSource=AudioSource.fromPfd(reader) }
        var latest=""
        try {
            r.startRecognition(request).collect { response ->
                when(response) {
                    is SpeechRecognizerResponse.PartialTextResponse -> {
                        latest=response.text
                        transcript.text=latest
                    }
                    is SpeechRecognizerResponse.FinalTextResponse -> {
                        latest=response.text
                        transcript.text=latest
                    }
                    is SpeechRecognizerResponse.ErrorResponse -> {
                        transcript.text="인식 오류: $response"
                    }
                    else -> {
                        // Other alpha response types do not require UI handling in this proof build.
                    }
                }
            }
        } finally {
            feeder.join()
            try { reader.close() } catch(_:Exception){}
            try { r.stopRecognition() } catch(_:Exception){}
            pcm.delete()
        }
        if(latest.isNotBlank()) status.text="✅ 온디바이스 전사 완료"
    }

    // m4a/AAC -> decoded PCM -> mono + 16kHz linear resampling -> raw PCM16LE
    private fun decodeTo16kMonoPcm(uri:Uri):File {
        val pfd=contentResolver.openFileDescriptor(uri,"r") ?: error("녹음파일 열기 실패")
        val ex=MediaExtractor()
        ex.setDataSource(pfd.fileDescriptor)
        var track=-1; var fmt:MediaFormat?=null
        for(i in 0 until ex.trackCount) {
            val f=ex.getTrackFormat(i)
            if((f.getString(MediaFormat.KEY_MIME)?:"").startsWith("audio/")) { track=i; fmt=f; break }
        }
        if(track<0 || fmt==null) error("오디오 트랙 없음")
        ex.selectTrack(track)
        val mime=fmt!!.getString(MediaFormat.KEY_MIME)!!
        val srcRate=fmt!!.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val srcCh=fmt!!.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val codec=MediaCodec.createDecoderByType(mime)
        codec.configure(fmt,null,null,0); codec.start()

        val temp=File(cacheDir,"bravo_${System.currentTimeMillis()}.pcm")
        FileOutputStream(temp).use { out ->
            val info=MediaCodec.BufferInfo()
            var inputDone=false; var outputDone=false
            var carry=0.0
            while(!outputDone) {
                if(!inputDone) {
                    val idx=codec.dequeueInputBuffer(10000)
                    if(idx>=0) {
                        val b=codec.getInputBuffer(idx)!!
                        val size=ex.readSampleData(b,0)
                        if(size<0) {
                            codec.queueInputBuffer(idx,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone=true
                        } else {
                            codec.queueInputBuffer(idx,0,size,ex.sampleTime,0); ex.advance()
                        }
                    }
                }
                val oi=codec.dequeueOutputBuffer(info,10000)
                if(oi>=0) {
                    val b=codec.getOutputBuffer(oi)!!
                    b.position(info.offset); b.limit(info.offset+info.size)
                    val bytes=ByteArray(info.size); b.get(bytes)
                    val bb=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    val frames=(bytes.size/2)/srcCh
                    val mono=ShortArray(frames)
                    for(i in 0 until frames) {
                        var sum=0
                        for(c in 0 until srcCh) sum+=bb.short.toInt()
                        mono[i]=(sum/srcCh).coerceIn(-32768,32767).toShort()
                    }
                    // nearest-neighbor resampling is sufficient for this technical proof
                    val step=srcRate/16000.0
                    var pos=carry
                    val ob=ByteBuffer.allocate(((frames/step)+2).toInt()*2).order(ByteOrder.LITTLE_ENDIAN)
                    while(pos<frames) { ob.putShort(mono[pos.toInt()]); pos+=step }
                    carry=pos-frames
                    out.write(ob.array(),0,ob.position())
                    codec.releaseOutputBuffer(oi,false)
                    if((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0) outputDone=true
                }
            }
        }
        codec.stop(); codec.release(); ex.release(); pfd.close()
        return temp
    }

    private fun tv(s:String,z:Int,b:Boolean)=TextView(this).apply {
        text=s; textSize=z.toFloat(); setPadding(6,10,6,10)
        if(b) setTypeface(typeface,1)
    }
    private fun btn(s:String, click:(View)->Unit)=Button(this).apply {
        text=s; isAllCaps=false; setOnClickListener(click)
    }
    private fun toast(s:String){Toast.makeText(this,s,Toast.LENGTH_LONG).show()}
}
