// 토스페이먼츠 결제 SDK 동적 로더.
//
// SDK는 CDN 스크립트로만 제공되므로 필요한 시점(충전 버튼 클릭)에 주입한다.
// index.html에 상시 넣지 않는 이유: 결제와 무관한 페이지에서 외부 스크립트를 받지 않기 위함.
const SDK_URL = 'https://js.tosspayments.com/v1/payment';

let loadPromise = null;

function loadScript() {
  if (window.TossPayments) return Promise.resolve(window.TossPayments);
  if (loadPromise) return loadPromise;

  loadPromise = new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = SDK_URL;
    script.async = true;
    script.onload = () => resolve(window.TossPayments);
    script.onerror = () => {
      loadPromise = null; // 실패 시 다음 시도에서 재로드 가능하게
      reject(new Error('SDK_LOAD_FAILED'));
    };
    document.head.appendChild(script);
  });
  return loadPromise;
}

/**
 * 결제창을 띄운다. 성공하면 브라우저가 successUrl로 리다이렉트되므로 이 Promise는 보통 resolve되지 않는다.
 * (OAuth의 인가서버 redirect와 동일한 구조 — 돌아온 뒤 처리는 successUrl 페이지가 맡는다.)
 *
 * clientKey가 비어 있으면 SDK 초기화 자체가 불가능하므로 NO_CLIENT_KEY로 조기 실패시킨다
 * (토스 테스트 키 미발급 상태를 화면에서 명확히 안내하기 위함).
 */
export async function requestTossPayment({ clientKey, orderId, amount, orderName, customerEmail }) {
  if (!clientKey) {
    throw new Error('NO_CLIENT_KEY');
  }
  const TossPayments = await loadScript();
  const toss = TossPayments(clientKey);

  return toss.requestPayment('카드', {
    amount,
    orderId,
    orderName,
    customerEmail,
    successUrl: `${window.location.origin}/payments/success`,
    failUrl: `${window.location.origin}/payments/fail`,
  });
}
