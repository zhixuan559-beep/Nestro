# Third-party local embedding model

Nestro's optional local semantic search uses **BGE-small-zh-v1.5**, a Chinese text embedding model released by BAAI under the MIT license. The Android runtime uses the INT8 ONNX conversion published by Xenova.

- Model: `Xenova/bge-small-zh-v1.5`, `onnx/model_int8.onnx`
- Model SHA-256: `b9837c19ce154ff0726d398ee77abbc03a7faf0476c6f93016c84e531be7ebb5`
- Tokenizer vocabulary: `BAAI/bge-small-zh-v1.5`, pinned revision `7999e1d`
- Inference: ONNX Runtime for Android
- Data handling: model download is user-triggered; after installation, embeddings and retrieval run locally. No document text is sent to a remote embedding API.
- The model is optional. Without it, the knowledge base continues to use local FTS4/BM25 retrieval.

The model is downloaded from Hugging Face over HTTPS and the ONNX weights are SHA-256 verified before installation. The model file is stored in the app-private files directory and is not included in user backups or project packages because vectors are derived from document text and can be rebuilt.
