package com.craneai.findr.ui.fake

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile

/** A sample moment in a video or recording: what is said or seen at `ms`. */
internal class FakeMoment(val ms: Long, val text: String)

/**
 * One sample file plus the "knowledge" the fake engine searches: `pages` (document text, page by page), `ocr` (text
 * read from an image), `concepts` (what a meaning search would find: visual or topical words) and `moments`.
 */
internal class FakeEntry(
  val file: IndexedFile,
  val pages: List<String> = emptyList(),
  val ocr: String? = null,
  val concepts: String = "",
  val moments: List<FakeMoment> = emptyList(),
)

/** Sample data in a Ugandan context. URIs are `fake://media/<id>`, which the UI draws as colour-block placeholders. */
internal object FakeData {
  private const val HOUR = 3_600_000L
  private const val DAY = 24 * HOUR
  private var nextId = 1L

  fun build(now: Long): List<FakeEntry> {
    nextId = 1L
    val list = ArrayList<FakeEntry>()

    fun file(
      name: String, path: String, kind: FileKind, mime: String, sizeKb: Long, ageMs: Long,
      w: Int? = null, h: Int? = null, durationMs: Long? = null, pageCount: Int? = null,
    ): IndexedFile {
      val id = nextId++
      return IndexedFile(
        id = id, uri = "fake://media/$id", relativePath = "$path/$name", name = name, kind = kind, mimeType = mime,
        sizeBytes = sizeKb * 1024, modifiedMillis = now - ageMs, width = w, height = h, durationMs = durationMs,
        pageCount = pageCount,
      )
    }
    fun pdf(name: String, path: String, ageMs: Long, pages: List<String>) =
      list.add(FakeEntry(file(name, path, FileKind.PDF, "application/pdf", 140L * pages.size, ageMs, pageCount = pages.size), pages = pages))
    fun doc(name: String, path: String, ageMs: Long, mime: String, text: String) =
      list.add(FakeEntry(file(name, path, FileKind.DOCUMENT, mime, 38, ageMs, pageCount = 1), pages = listOf(text)))
    fun shot(name: String, ageMs: Long, ocr: String, concepts: String = "") =
      list.add(FakeEntry(file(name, "Pictures/Screenshots", FileKind.SCREENSHOT, "image/png", 310, ageMs, 720, 1600), ocr = ocr, concepts = concepts))
    fun photo(name: String, path: String, ageMs: Long, concepts: String, ocr: String? = null, w: Int = 4000, h: Int = 3000) =
      list.add(FakeEntry(file(name, path, FileKind.IMAGE, "image/jpeg", 2400, ageMs, w, h), ocr = ocr, concepts = concepts))
    fun video(name: String, path: String, ageMs: Long, durationMs: Long, concepts: String, moments: List<FakeMoment>) =
      list.add(FakeEntry(file(name, path, FileKind.VIDEO, "video/mp4", durationMs / 40, ageMs, 1080, 1920, durationMs), concepts = concepts, moments = moments))
    fun audio(name: String, path: String, ageMs: Long, durationMs: Long, mime: String, concepts: String, moments: List<FakeMoment> = emptyList()) =
      list.add(FakeEntry(file(name, path, FileKind.AUDIO, mime, durationMs / 120, ageMs, durationMs = durationMs), concepts = concepts, moments = moments))

    // ---- Documents ----
    pdf("MTN MoMo Statement Sept 2026.pdf", "Download/MTN", 2 * HOUR, listOf(
      "MTN Mobile Money statement, 01 Sep 2026 to 30 Sep 2026. Account 0772 *** 418. Opening balance UGX 214,500.",
      "05 Sep Cash out UGX 150,000 at agent Kato Ssemakula, Ntinda. Fee UGX 2,500. Balance UGX 62,000.",
      "11 Sep Payment to Kampala Parents School UGX 480,000, reference term three fees. Fee UGX 0.",
      "19 Sep Pay bill UMEME yaka token UGX 50,000. Airtime purchase UGX 10,000.",
      "24 Sep Sent to Nakato Grace UGX 80,000. Received from Mama Gift UGX 300,000.",
      "Closing balance UGX 38,200. Statement generated on the MTN MoMo app. Keep this for your records.",
    ))
    pdf("MTN MoMo Statement Aug 2026.pdf", "Download/MTN", 41 * DAY, listOf(
      "MTN Mobile Money statement, 01 Aug 2026 to 31 Aug 2026. Account 0772 *** 418. Opening balance UGX 96,000.",
      "03 Aug Received salary advance UGX 700,000. Cash out UGX 200,000 at agent Kato Ssemakula, Ntinda.",
      "14 Aug Payment to Kampala Parents School UGX 120,000, reference uniform and books.",
      "22 Aug Pay bill NWSC water UGX 35,000. Airtime UGX 5,000.",
      "Closing balance UGX 214,500.",
    ))
    pdf("Kampala Parents School Term 3 fees receipt.pdf", "Download/WhatsApp Documents", 9 * DAY / 4, listOf(
      "Kampala Parents School. Receipt No. 20418, Term Three 2026. Received from Mr Ssebunya Moses for Nakato Grace, Primary 5: tuition UGX 480,000. Balance nil. Bursar signature.",
    ))
    pdf("Term 2 school fees receipt.pdf", "Download/WhatsApp Documents", 120 * DAY, listOf(
      "Kampala Parents School. Receipt No. 17733, Term Two 2026. Received from Mr Ssebunya Moses for Nakato Grace, Primary 5: tuition UGX 450,000. Balance UGX 30,000 to be cleared before Term Three.",
    ))
    pdf("Nakato - Ssebunya Wedding Programme.pdf", "Download", 6 * DAY, listOf(
      "The Wedding of Nakato Esther and Ssebunya Brian. Saturday 3 October 2026. Order of service.",
      "09:30 Guests arrive at Rubaga Cathedral grounds. 10:00 Entrance of the bride. Hymn: Praise My Soul the King of Heaven.",
      "10:20 Reading and homily by Rev. Kiwanuka. 10:50 Exchange of vows and rings. Signing of the register.",
      "12:00 Photographs at Kasubi gardens. 13:30 Reception begins at Imperial Resort Beach Hotel, Entebbe.",
      "14:00 Introduction of families. Speeches by the father of the bride, best man and matron of honour.",
      "15:30 Cutting of the cake. Lunch: matooke, rice, pork and chicken. Soft drinks and water.",
      "17:00 First dance. MC: Mr Lule. DJ: Sound Masters. Vote of thanks by the groom.",
      "Gifts and envelopes: please give to the committee table. Mobile money pledges: 0772 000 111.",
      "Wedding committee: Chair Mrs Nalubega, Treasurer Mr Okello, Protocol Ms Achieng.",
      "Directions: from Kampala take the Entebbe Expressway, exit at Abayita Ababiri.",
      "Dress code: gomesi and kanzu for the family; guests in elegant colours.",
      "With thanks to all who have supported us with love, time and gifts.",
    ))
    doc("Mulago clinic letter.docx", "Documents", 3 * DAY, "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      "Mulago National Referral Hospital, Outpatient Clinic. Date 28 September 2026. To whom it may concern: Mr Ssebunya Moses attended clinic with fever and headache. Diagnosis: uncomplicated malaria, treated with artemether-lumefantrine. Review in two weeks. Dr Atim.")
    pdf("Tenancy agreement Ntinda.pdf", "Documents", 210 * DAY, listOf(
      "Tenancy agreement between Mr Mukasa Joseph (landlord) and Mr Ssebunya Moses (tenant), Ntinda. Monthly rent UGX 900,000 payable in advance.",
      "The tenant shall pay a deposit of two months. Water and electricity bills (NWSC and UMEME) are paid by the tenant.",
      "Either party may terminate this agreement with three months written notice. Signed and witnessed.",
    ))
    pdf("NSSF contributions statement 2026.pdf", "Download", 70 * DAY, listOf(
      "National Social Security Fund. Member statement 2026. Employer contributions UGX 1,260,000. Employee contributions UGX 840,000. Total balance UGX 14,820,500.",
      "Contributions are credited monthly. Interest for 2025 was declared at 10.5 percent.",
    ))
    doc("Savings group minutes Oct.docx", "Documents", 5 * DAY, "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      "Tusitukirewamu savings group minutes. Present: 14 members. Monthly contribution UGX 50,000. Loan to Mama Gift UGX 400,000 at 5 percent. Next meeting at Namugongo on Sunday after church.")
    pdf("UMEME yaka token receipt.pdf", "Download", 4 * DAY, listOf(
      "UMEME prepaid. Token 2849 1175 0382 9910 3347. Amount UGX 50,000. Units 112.4 kWh. Meter 0412 3345 987. Thank you for paying.",
    ))

    // ---- Screenshots ----
    shot("Screenshot_20261012-1815_WhatsApp.png", 3 * HOUR,
      "Mama Gift: Did you send the school fees receipt? The bursar is asking. Pay before Friday please. You: Sending now, I paid through MoMo yesterday.",
      "chat conversation message")
    shot("Screenshot_20261011-0932_MTN MoMo.png", 26 * HOUR,
      "Transaction successful. You have sent UGX 80,000 to NAKATO GRACE 0772 345 118. Transaction ID 71829384. Fee UGX 0. New balance UGX 62,000.",
      "mobile money payment confirmation")
    shot("Screenshot_20261009-2007_Bolt.png", 4 * DAY,
      "Your ride with Bolt. Kololo to Ntinda, 6.2 km. Total UGX 12,500. Paid with cash. Driver Isaac, UBA 412K.",
      "taxi ride receipt")
    shot("Screenshot_20261006-1144_Chrome.png", 8 * DAY,
      "Matooke and groundnut sauce: peel and steam the matooke wrapped in banana leaves for two hours. Serve with groundnut sauce.",
      "recipe food cooking")
    shot("Screenshot_20261002-1630_Airtel.png", 12 * DAY,
      "Airtel Money. Bundle purchased: 5 GB for UGX 25,000, valid 30 days. Dial *185# to check your balance.",
      "data bundle airtime")
    shot("Screenshot_20260930-0850_WhatsApp.png", 14 * DAY,
      "Pastor Moses: Sunday service starts at 9:00 at Namugongo. Please bring your offering envelope. Youth fellowship afterwards.",
      "church announcement chat")

    // ---- Memes ----
    photo("meme_boda_wandegeya.jpg", "Pictures/WhatsApp Images", 5 * HOUR,
      "meme funny joke motorcycle traffic jam", "When the boda man says two minutes and you are still in the Wandegeya jam", 1080, 1080)
    photo("FB_IMG_1760219031.jpg", "Pictures/Facebook", 2 * DAY,
      "meme funny joke money", "Me checking my MoMo balance after paying rent and school fees", 960, 960)
    photo("meme_kampala_rain.jpg", "Pictures/WhatsApp Images", 6 * DAY,
      "meme funny joke rain weather", "Kampala rain: five millimetres falls and the whole city shuts down", 1080, 1350)
    photo("meme_monday_matooke.jpg", "Pictures/WhatsApp Images", 9 * DAY,
      "meme funny joke food banana", "Monday morning without matooke is not Monday", 1080, 1080)

    // ---- Photos ----
    photo("IMG_20261003_172841.jpg", "DCIM/Camera", 11 * DAY,
      "sunset lake victoria entebbe water evening orange sky boats")
    photo("IMG_20261003_121502.jpg", "DCIM/Camera", 11 * DAY,
      "wedding kwanjula bride gomesi aunties family celebration flowers")
    photo("IMG_20261003_125940.jpg", "DCIM/Camera", 11 * DAY,
      "wedding cake reception hotel tables guests celebration")
    photo("IMG_20260928_184411.jpg", "DCIM/Camera", 16 * DAY,
      "birthday cake candles family children kitchen indoor")
    photo("IMG_20260921_101230.jpg", "DCIM/Camera", 23 * DAY,
      "kasubi tombs thatched royal building heritage site kampala")
    photo("PXL_20260918_083355.jpg", "DCIM/Camera", 26 * DAY,
      "market produce vegetables matooke tomatoes owino women selling")
    photo("IMG_20260912_164850.jpg", "DCIM/Camera", 32 * DAY,
      "ankole cows long horns pasture green hills farm")
    photo("IMG_20260830_190622.jpg", "DCIM/Camera", 45 * DAY,
      "city street night kampala traffic jam boda lights")
    photo("IMG_20260815_093010.jpg", "DCIM/Camera", 60 * DAY,
      "baby smiling child playing home indoor")

    // ---- Videos ----
    video("VID_20261003_130212.mp4", "DCIM/Camera", 11 * DAY, 252_000,
      "wedding kwanjula introduction ceremony family celebration singing", listOf(
        FakeMoment(42_000, "bride enters with her aunties dancing wedding"),
        FakeMoment(130_000, "speech by the father of the bride welcoming the groom's family"),
        FakeMoment(205_000, "exchange of gifts and cutting of the cake"),
      ))
    video("VID_20261001_171544.mp4", "DCIM/Camera", 13 * DAY, 31_000,
      "boda boda motorcycle ride road kampala traffic", listOf(FakeMoment(9_000, "boda weaving through traffic jam on Jinja Road")))
    video("VID_20260925_093001.mp4", "DCIM/Camera", 19 * DAY, 85_000,
      "graduation ceremony gowns university makerere", listOf(
        FakeMoment(12_000, "graduands march in gowns"),
        FakeMoment(61_000, "name called and certificate handed over graduation"),
      ))
    video("WhatsApp Video 2026-10-01 at 17.20.mp4", "Movies/WhatsApp Video", 13 * DAY, 47_000,
      "school concert children dancing stage performance", listOf(FakeMoment(20_000, "primary five class sings the national anthem on stage")))

    // ---- Audio ----
    audio("WhatsApp Ptt 2026-10-08 at 19.12.opus", "Music/WhatsApp Audio", 5 * DAY, 47_000, "audio/ogg",
      "voice note message", listOf(FakeMoment(8_000, "mum says remember to send the school fees")))
    audio("Sunday sermon Namugongo.m4a", "Recordings", 10 * DAY, 2_300_000, "audio/mp4",
      "sermon church preaching pastor")

    // ---- Filler, so the photo grid has some depth to scroll ----
    val scenes = listOf(
      "family gathering outdoors compound children",
      "lake shore water boats fishermen",
      "street food rolex chapati stall",
      "church congregation singing sunday",
      "garden flowers green leaves rain",
      "friends selfie smiling restaurant",
    )
    for (i in 0 until 18) {
      photo("IMG_2026%02d%02d_%06d.jpg".format(7 + i / 9, 1 + (i * 3) % 27, 100000 + i * 7919), "DCIM/Camera", (70 + i * 6) * DAY, scenes[i % scenes.size])
    }

    return list
  }
}
