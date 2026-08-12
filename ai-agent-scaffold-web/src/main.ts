import { createPinia } from 'pinia';
import { createApp } from 'vue';

import App from '@/App.vue';
import router from '@/router';
import { useAuthStore } from '@/stores/auth';
import '@fontsource-variable/archivo/wght.css';
import '@fontsource-variable/newsreader/wght.css';
import '@/styles/base.css';

const app = createApp(App);
const pinia = createPinia();

app.use(pinia);

const authStore = useAuthStore();
authStore.bindHttpListener();

app.use(router);
app.mount('#app');
