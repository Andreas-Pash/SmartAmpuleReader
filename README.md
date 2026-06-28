# Smart Ampoule Reader

An offline-first Android application for extracting structured medicine information from ampoule labels using computer vision, OCR, and on-device Large Language Models (LLMs).

The application captures multiple images of an ampoule, performs OCR across all images, combines the extracted text, and uses a lightweight LLM to produce a structured JSON output.

---

## Features

- 📷 Capture **1–8 images** of the same ampoule
- 🗑️ Add and remove images before processing
- 🔍 Offline OCR pipeline
- 🤖 Local LLM extraction (Qwen 2.5 1.5B)
- 📄 Structured JSON output
- 🔒 Privacy-first (no cloud processing)
- 📱 Designed for Android devices

---

## Pipeline

```
Capture Images  
          │
          ▼
Image Preprocessing
(OpenCV)
          │
          ▼
RapidOCR
(Detection + Recognition)
          │
          ▼
OCR Lines
(text + confidence)
          │
          ▼
Qwen 2.5 1.5B
(JSON extraction)
          │
          ▼
Structured Ampoule Information
```

---

## Example Output

```json
{
  "product_name": "Propofol 1%",
  "active_ingredient": "Propofol",
  "strength": "10 mg/ml",
  "volume": "20 ml",
  "batch_lot_number": "10UK4931",
  "expiry_date": "09/2028",
  "manufacturer": "Fresenius Kabi",
  "route": "Intravenous use",
  "legal_category": "POM",
  "marketing_authorisation_uk": "PL 08828/0167",
  "marketing_authorisation_ie": "PA 2059/017/001",
  "confidence.product_name": 0.98,
  "confidence.batch_lot_number": 0.99
}
```

---

## Tech Stack

### Android

- Kotlin
- CameraX
- Coroutines
- Gson

### Computer Vision

- OpenCV
- RapidOCR
- ONNX Runtime Mobile

### AI

- Qwen 2.5 1.5B
- Ollama (development)
- Planned fully on-device inference

---

## Project Structure

```
app/
│
├── src/
│   ├── main/
│   │   ├── assets/
│   │   │   └── rapidocr/
│   │   │       ├── det_model.onnx
│   │   │       ├── cls_model.onnx
│   │   │       ├── rec_model.onnx
│   │   │       └── rec_dict.txt
│   │   │
│   │   └── java/
│   │       └── ...
│   │
│   └── test/
│
└── build.gradle.kts
```

---

## Current Workflow

1. Capture between **1 and 8** images.
2. Review the captured images.
3. Remove any unwanted images.
4. Press **Extract Ampoule Info**.
5. OCR is run across every image.
6. OCR results are merged.
7. The LLM extracts structured medicine information.
8. JSON is displayed to the user.

---

## Planned Improvements

- Better glare removal
- Automatic ROI detection
- Barcode / Data Matrix recognition
- Native ONNX RapidOCR implementation
- Fully offline Qwen inference
- Confidence visualisation
- Export JSON
- Batch processing
- Support for vials, syringes and cartons

---

## Motivation

Healthcare professionals often need to manually transcribe medicine information from ampoules, particularly batch numbers and expiry dates.

Smart Ampoule Reader aims to automate this process by combining computer vision and local AI to provide fast, accurate, and privacy-preserving extraction directly on the device.

---

## Status

🚧 Active development

Current focus:

- Multi-image capture
- RapidOCR integration
- On-device LLM inference
- Performance optimisation

---

## License

This project is currently proprietary and is not licensed for redistribution.
